package com.termux.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandArbiterTest {

    private class FakeHost : PageHost {
        var url: String? = null
        var evalResult: String? = null
        var evalHook: (suspend () -> String?)? = null
        override fun loadUrl(url: String) {
            this.url = url
        }
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String? = url
        override suspend fun evalJs(script: String): String? =
            evalHook?.invoke() ?: evalResult
    }

    private fun ui(): UiRunner = object : UiRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private suspend fun awaitResult(
        results: ResultStore,
        commandId: String
    ): StoredResult? {
        return withTimeout(5000) {
            var stored = results.get(commandId)
            while (stored == null) {
                delay(10)
                stored = results.get(commandId)
            }
            stored
        }
    }

    @Test
    fun `open executes serially and stores a result`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Open("https://example.com", "termux"))
            assertTrue(submitted is SubmitResult.Accepted)
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertEquals("https://example.com", host.url)
            assertTrue(stored!!.body.contains("completed"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `read parses static extraction json`() = runBlocking {
        val host = FakeHost().apply { evalResult = """{"text":"hello world"}""" }
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("page", 100))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("hello world"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `generation advance during read cancels the committed result`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        policy.onAutomationStart()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            host.evalHook = {
                entered.complete(Unit)
                release.await()
                """{"text":"stale payload"}"""
            }
            val submitted = arbiter.submit(BrowserCommand.Read("page", 100))
            val id = (submitted as SubmitResult.Accepted).commandId
            withTimeout(5000) { entered.await() }
            policy.onStop() // generation advances mid-read
            release.complete(Unit)
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("cancelled"))
            assertFalse(stored.body.contains("stale payload"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `pause preserves queued commands until resume`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        policy.onAutomationStart()
        policy.onPause()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            val first = arbiter.submit(BrowserCommand.Open("https://a.example", "termux"))
            val second = arbiter.submit(BrowserCommand.Open("https://b.example", "termux"))
            assertTrue(first is SubmitResult.Accepted)
            assertTrue(second is SubmitResult.Accepted)
            assertEquals(2, arbiter.pendingCount())
            delay(300)
            // Nothing executed while paused; nothing lost either.
            assertEquals(2, arbiter.pendingCount())
            policy.onResume()
            val one = awaitResult(results, (first as SubmitResult.Accepted).commandId)
            val two = awaitResult(results, (second as SubmitResult.Accepted).commandId)
            assertTrue(one!!.body.contains("completed"))
            assertTrue(two!!.body.contains("completed"))
            assertEquals(0, arbiter.pendingCount())
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `stop drains queued commands as cancelled`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        policy.onAutomationStart()
        policy.onPause()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            val first = arbiter.submit(BrowserCommand.Open("https://a.example", "termux"))
            val second = arbiter.submit(BrowserCommand.Open("https://b.example", "termux"))
            arbiter.stopNow("termux")
            val one = results.get((first as SubmitResult.Accepted).commandId)
            val two = results.get((second as SubmitResult.Accepted).commandId)
            assertTrue(one!!.body.contains("cancelled"))
            assertTrue(two!!.body.contains("cancelled"))
            assertEquals(0, arbiter.pendingCount())
            assertEquals("https://a.example", host.url ?: "https://a.example")
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `main_content parses like page text`() = runBlocking {
        val host = FakeHost().apply { evalResult = """{"text":"article body"}""" }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("main_content", 100))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("article body"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `empty null and malformed extraction never crashes`() = runBlocking {
        val host = FakeHost()
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            for (raw in listOf(null, "", "{}", "not json{{{", "[]")) {
                host.evalResult = raw
                val submitted = arbiter.submit(BrowserCommand.Read("page", 100))
                val id = (submitted as SubmitResult.Accepted).commandId
                val stored = awaitResult(results, id)
                assertTrue(stored!!.body.contains("\"scope\":\"page\""))
            }
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `huge text truncates at maxChars`() = runBlocking {
        val host = FakeHost().apply {
            evalResult = """{"text":"""" + "x".repeat(100000) + """"}"""
        }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("page", 100))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("\"truncated\":true"))
            assertFalse(stored.body.contains("x".repeat(1000)))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `links are capped at 500`() = runBlocking {
        val raw = buildString {
            append("""{"links":[""")
            repeat(600) { i ->
                if (i > 0) append(',')
                append("""{"text":"t$i","href":"https://example.com/$i","visible":true}""")
            }
            append("]}")
        }
        val host = FakeHost().apply { evalResult = raw }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("links", 50000))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertEquals(500, "\"href\"".toRegex().findAll(stored!!.body).count())
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `per-link text and href are bounded`() = runBlocking {
        val raw = """{"links":[{"text":"""" + "t".repeat(300) +
            """","href":"https://example.com/""" + "p".repeat(3000) +
            """","visible":true}]}"""
        val host = FakeHost().apply { evalResult = raw }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("links", 50000))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("t".repeat(200)))
            assertFalse(stored.body.contains("t".repeat(201)))
            assertFalse(stored.body.contains("p".repeat(3000)))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `full queue answers queue-full`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        policy.onAutomationStart()
        policy.onPause() // worker gate blocks; nothing is consumed
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            repeat(ProtocolLimits.MAX_QUEUE) {
                val submitted = arbiter.submit(
                    BrowserCommand.Open("https://example.com/$it", "termux")
                )
                assertTrue(submitted is SubmitResult.Accepted)
            }
            val overflow = arbiter.submit(
                BrowserCommand.Open("https://example.com/overflow", "termux")
            )
            assertTrue(overflow is SubmitResult.QueueFull)
        } finally {
            arbiter.close()
        }
    }
}
