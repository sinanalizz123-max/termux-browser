package com.termux.browser

import com.termux.browser.ai.AdapterRegistry
import com.termux.browser.ai.GenericAdapter
import com.termux.browser.ai.JsPrompt
import com.termux.browser.ai.PluginAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin adapter drives a scripted chat end-to-end through the real
 * arbiter: detection, health, submit, wait, extract — with zero built-in
 * provider code for the plugin host.
 */
class PluginArbiterTest {

    private class FakeHost(val pageUrl: String = "https://chat.plugintest.com/") : PageHost {
        val scripts = mutableListOf<String>()
        val snapshots = ArrayDeque<String>()
        override fun loadUrl(url: String) {}
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String = pageUrl
        override suspend fun evalJs(script: String): String? {
            scripts.add(script)
            if (script == JsPrompt.CLEAR_SCRIPT) return """{"cleared":true}"""
            if (script.contains("window.__tbPrompt=")) return """{"submitted":true}"""
            return if (snapshots.isEmpty()) null else snapshots.removeFirst()
        }
    }

    private fun plugin(): PluginAdapter {
        return PluginAdapter(
            manifestId = "plugintest",
            hostSet = setOf("chat.plugintest.com"),
            selectorSet = com.termux.browser.ai.SelectorSet(
                prompt = listOf("textarea"),
                submit = listOf("div.send"),
                messages = listOf("[class*='ds-markdown']"),
                stop = emptyList()
            ),
            marker = "[class*='ds-']",
            version = 3
        )
    }

    private fun healthy(text: String = "old") =
        """{"messageCount":2,"lastText":"$text","generating":false,"promptFound":true,"submitFound":true,"markerFound":true}"""

    @Test
    fun `plugin adapter completes a scripted chat`() = runBlocking {
        val host = FakeHost()
        host.snapshots.addAll(
            listOf(
                healthy(),
                healthy(),
                healthy("answ"),
                healthy("answer"),
                healthy("answer!"),
                healthy("answer!"),
                healthy("answer!"),
                healthy("answer!"),
                healthy("answer!")
            )
        )
        val results = ResultStore()
        val policy = ControlPolicy()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val ui = object : UiRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        val arbiter = CommandArbiter(
            this, ui, host, controller, policy, results,
            registry = AdapterRegistry(listOf(plugin(), GenericAdapter()))
        )
        try {
            val submitted = arbiter.submit(
                BrowserCommand.AiChat("plugintest", "Hi.", 20000, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = withTimeout(15000) {
                var s = results.get(id)
                while (s == null) {
                    delay(100)
                    s = results.get(id)
                }
                s
            }
            assertTrue(stored.body.contains("completed"))
        } finally {
            arbiter.close()
        }
    }
}
