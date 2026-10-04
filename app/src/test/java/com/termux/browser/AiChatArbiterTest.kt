package com.termux.browser

import com.termux.browser.ai.AdapterRegistry
import com.termux.browser.ai.ChatGPTAdapter
import com.termux.browser.ai.GenericAdapter
import com.termux.browser.ai.JsPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8 ai-chat through the real arbiter with a scripted page: the fake host
 * answers snapshot evals from a timeline queue and submit evals with an ack.
 * No WebView, no network — deterministic end-to-end controller coverage.
 */
class AiChatArbiterTest {

    private class FakeHost(val pageUrl: String = "https://chatgpt.com/c/123") : PageHost {
        val scripts = mutableListOf<String>()
        val snapshots = ArrayDeque<String>()
        var submitAck = """{"submitted":true}"""
        var submitted = 0
        var cleared = 0

        override fun loadUrl(url: String) {}
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String = pageUrl
        override suspend fun evalJs(script: String): String? {
            scripts.add(script)
            if (script == JsPrompt.CLEAR_SCRIPT) {
                cleared++
                return """{"cleared":true}"""
            }
            if (script.contains("window.__tbPrompt=")) {
                submitted++
                return submitAck
            }
            return if (snapshots.isEmpty()) snapshots.lastOrNull() else snapshots.removeFirst()
        }
    }

    private fun healthy(count: Int = 2, text: String = "old") =
        """{"messageCount":$count,"lastText":"$text","generating":false,"promptFound":true,"submitFound":true,"composerEmpty":true}"""

    private fun streaming(text: String, generating: Boolean = true) =
        """{"messageCount":3,"lastText":"$text","generating":$generating,"promptFound":true,"submitFound":true,"composerEmpty":true}"""

    private fun arbiter(
        host: FakeHost,
        scope: kotlinx.coroutines.CoroutineScope,
        results: ResultStore
    ): CommandArbiter {
        val policy = ControlPolicy()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val ui = object : UiRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        return CommandArbiter(
            scope, ui, host, controller, policy, results,
            registry = AdapterRegistry(listOf(ChatGPTAdapter(), GenericAdapter()))
        )
    }

    private suspend fun awaitResult(results: ResultStore, id: String): StoredResult {
        return withTimeout(15000) {
            var stored = results.get(id)
            while (stored == null) {
                delay(100)
                stored = results.get(id)
            }
            stored
        }
    }

    @Test
    fun `happy path submits waits and completes`() = runBlocking {
        val host = FakeHost()
        host.snapshots.addAll(
            listOf(
                healthy(),
                healthy(),
                streaming("answ"),
                streaming("answer"),
                streaming("answer!", generating = false),
                streaming("answer!", generating = false),
                streaming("answer!", generating = false),
                streaming("answer!", generating = false),
                streaming("answer!", generating = false)
            )
        )
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Summarize this.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("completed"))
            assertTrue(stored.body.contains("answer!"))
            assertEquals(1, host.submitted)
            assertTrue(host.cleared >= 1)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `login wall fails without submitting`() = runBlocking {
        val host = FakeHost()
        host.snapshots.add(
            """{"messageCount":0,"lastText":"","generating":false,"promptFound":false,"submitFound":false,"loginRequired":true}"""
        )
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("LOGIN_REQUIRED"))
            assertEquals(0, host.submitted)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `missing submit control fails closed`() = runBlocking {
        val host = FakeHost()
        host.snapshots.add(
            """{"messageCount":1,"lastText":"hi","generating":false,"promptFound":true,"submitFound":false}"""
        )
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("ADAPTER_UNRECOGNIZED"))
            assertEquals(0, host.submitted)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `unknown site never submits`() = runBlocking {
        val host = FakeHost("https://example.com/")
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("ADAPTER_UNRECOGNIZED"))
            assertEquals(0, host.submitted)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `oversize prompt is rejected before touching the page`() = runBlocking {
        val host = FakeHost()
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "x".repeat(20001), 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("INVALID_REQUEST"))
            assertTrue(host.scripts.isEmpty())
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `learned submit selector leads the submit script`() = runBlocking {
        val host = FakeHost()
        host.snapshots.add(healthy())
        val results = ResultStore()
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "tb-learn-${System.nanoTime()}"
        )
        dir.mkdirs()
        try {
            val learned = com.termux.browser.ai.LearnedStore(dir)
            learned.put("chatgpt.com", "div.learned-send")
            val policy = ControlPolicy()
            val controller = BrowserController(host, policy, ActivityLog(), announce = {})
            val ui = object : UiRunner {
                override suspend fun <T> run(block: suspend () -> T): T = block()
            }
            val arbiter = CommandArbiter(
                this, ui, host, controller, policy, results,
                registry = AdapterRegistry(listOf(ChatGPTAdapter(), GenericAdapter())),
                learnedStore = learned
            )
            try {
                val submitted = arbiter.submit(
                    BrowserCommand.AiChat("chatgpt", "Hi.", 100, "termux")
                )
                assertTrue(submitted is SubmitResult.Accepted)
                // The learned selector must lead the ACTUAL submit script
                // sent to the page — observe scripts, not command results.
                withTimeout(10000) {
                    while (host.scripts.none { it.contains("window.__tbPrompt=") }) {
                        delay(50)
                    }
                }
                val submitScript = host.scripts.first { it.contains("window.__tbPrompt=") }
                assertTrue(submitScript.contains("div.learned-send"))
            } finally {
                arbiter.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unconfirmed send fails fast without waiting`() = runBlocking {
        val host = FakeHost()
        // Health passes, ack claims submitted, but no new message ever
        // appears and the composer never drains: the send did not happen.
        // NOTE: no composerEmpty here — the composer keeps its text.
        host.snapshots.add(
            """{"messageCount":2,"lastText":"old","generating":false,"promptFound":true,"submitFound":true,"composerEmpty":false}"""
        )
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Hi.", 100, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("SUBMIT_UNCONFIRMED"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `generation advance during wait cancels`() = runBlocking {
        val host = FakeHost()
        host.snapshots.addAll(
            listOf(healthy(), healthy(), streaming("partial"), streaming("partial"))
        )
        val results = ResultStore()
        val policy = ControlPolicy()
        policy.onAutomationStart()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val ui = object : UiRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        val arbiter = CommandArbiter(
            this, ui, host, controller, policy, results,
            registry = AdapterRegistry(listOf(ChatGPTAdapter(), GenericAdapter()))
        )
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            // Let the waiter start polling, then simulate takeover.
            delay(1500)
            policy.onUserNavigation()
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("cancelled"))
            assertFalse(stored.body.contains("completed"))
        } finally {
            arbiter.close()
        }
    }
}
