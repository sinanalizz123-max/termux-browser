package com.termux.browser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandArbiterTest {

    private class FakeHost : PageHost {
        var url: String? = null
        var evalResult: String? = null
        override fun loadUrl(url: String) {
            this.url = url
        }
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String? = url
        override suspend fun evalJs(script: String): String? = evalResult
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
        val controller = BrowserController(host, policy, ActivityLog()) {}
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
        val controller = BrowserController(host, policy, ActivityLog()) {}
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
    fun `full queue answers queue-full`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        policy.onAutomationStart()
        policy.onPause() // worker gate blocks; nothing is consumed
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog()) {}
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
