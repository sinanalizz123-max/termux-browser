package com.termux.browser

import com.termux.browser.ai.AdapterRegistry
import com.termux.browser.ai.ChatGPTAdapter
import com.termux.browser.ai.DeepSeekAdapter
import com.termux.browser.ai.GenericAdapter
import com.termux.browser.ai.JsPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M9 DeepSeek path through the real arbiter with a scripted page.
 */
class DeepSeekArbiterTest {

    private class FakeHost(val pageUrl: String = "https://chat.deepseek.com/a/chat/s/1") : PageHost {
        val scripts = mutableListOf<String>()
        val snapshots = ArrayDeque<String>()
        var submitted = 0
        var submitSucceeds = true

        override fun loadUrl(url: String) {}
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String = pageUrl
        override suspend fun evalJs(script: String): String? {
            scripts.add(script)
            if (script == JsPrompt.CLEAR_SCRIPT) return """{"cleared":true}"""
            if (script.contains("window.__tbPrompt=")) {
                if (script.contains("R.filled")) {
                    return """{"filled":true,"verifyLen":5}"""
                }
                submitted++
                return if (submitSucceeds) {
                    """{"submitted":true}"""
                } else {
                    """{"submitted":false,"reason":"no-submit"}"""
                }
            }
            return if (snapshots.isEmpty()) null else snapshots.removeFirst()
        }
    }

    private fun healthy(text: String = "old") =
        """{"messageCount":2,"lastText":"$text","generating":false,"promptFound":true,"submitFound":true,"markerFound":true,"composerEmpty":true}"""

    private fun streaming(text: String, generating: Boolean = true) =
        """{"messageCount":3,"lastText":"$text","generating":$generating,"promptFound":true,"submitFound":true,"markerFound":true,"composerEmpty":true}"""

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
            registry = AdapterRegistry(listOf(ChatGPTAdapter(), DeepSeekAdapter(), GenericAdapter()))
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
    fun `deepseek happy path completes`() = runBlocking {
        val host = FakeHost()
        host.snapshots.addAll(
            listOf(
                healthy(),
                healthy(),
                streaming("da"),
                streaming("data"),
                streaming("data!", generating = false),
                streaming("data!", generating = false),
                streaming("data!", generating = false),
                streaming("data!", generating = false),
                streaming("data!", generating = false)
            )
        )
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("deepseek", "Explain this.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("completed"))
            assertTrue(stored.body.contains("data!"))
            assertEquals(1, host.submitted)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `missing marker fails closed with no submission`() = runBlocking {
        val host = FakeHost()
        host.snapshots.add(
            """{"messageCount":0,"lastText":"","generating":false,"promptFound":true,"submitFound":true,"markerFound":false}"""
        )
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("deepseek", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("HEALTH_CHECK_FAILED"))
            assertEquals(0, host.submitted)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `chatgpt requested on deepseek page is rejected`() = runBlocking {
        val host = FakeHost()
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("chatgpt", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("INVALID_REQUEST"))
            assertEquals(0, host.submitted)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `bridge is cleaned after failed submission`() = runBlocking {
        val host = FakeHost()
        host.submitSucceeds = false
        // Health passes, but submission itself reports no-submit control.
        host.snapshots.add(healthy())
        val results = ResultStore()
        val arbiter = arbiter(host, this, results)
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("deepseek", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored.body.contains("SUBMIT_FALSE"))
            // The submit script ran (self-cleaning finally) plus the native
            // best-effort clear.
            assertTrue(host.scripts.any { it.contains("delete window.__tbPrompt") })
            assertTrue(host.scripts.any { it == JsPrompt.CLEAR_SCRIPT })
        } finally {
            arbiter.close()
        }
    }
}
