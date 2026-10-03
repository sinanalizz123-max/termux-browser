package com.termux.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserControllerTest {

    private class FakeHost : PageHost {
        var url: String? = null
        var stopped = false
        var evalResult: String? = null
        override fun loadUrl(url: String) {
            this.url = url
        }
        override fun stopLoading() {
            stopped = true
        }
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String? = url
        override suspend fun evalJs(script: String): String? = evalResult
    }

    private fun controller(
        host: FakeHost = FakeHost(),
        announced: MutableList<String> = mutableListOf()
    ): Triple<BrowserController, ControlPolicy, ActivityLog> {
        val policy = ControlPolicy()
        val log = ActivityLog()
        return Triple(BrowserController(host, policy, log, announced::add), policy, log)
    }

    @Test
    fun `stale page finish after user navigation is ignored`() {
        val host = FakeHost()
        val announced = mutableListOf<String>()
        val (controller, policy, log) = controller(host, announced)
        // Termux automation context owns the WebView for this command.
        policy.onAutomationStart()
        controller.open("https://a.example", "termux")
        controller.userNavigated("https://b.example")
        // Delayed callback from A arrives after the user moved to B.
        controller.onPageFinished("https://a.example")
        assertTrue(
            "stale A result must never be published",
            announced.none { it == "Page ready: https://a.example" }
        )
        assertTrue(
            log.since(0).any { it.action == "stale_callback_ignored" }
        )
        assertTrue(announced.any { it.contains("https://b.example") })
    }

    @Test
    fun `rejected input never reaches the host`() {
        val host = FakeHost()
        val (controller, _, _) = controller(host)
        assertFalse(controller.open("file:///etc/passwd", "termux"))
        assertTrue(host.url == null)
    }

    @Test
    fun `stop invalidates the active command`() {
        val host = FakeHost()
        val announced = mutableListOf<String>()
        val (controller, _, _) = controller(host, announced)
        controller.open("https://a.example", "termux")
        controller.stop("termux")
        controller.onPageFinished("https://a.example")
        assertTrue(host.stopped)
        assertTrue(announced.none { it == "Page ready: https://a.example" })
    }
}
