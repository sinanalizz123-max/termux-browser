package com.termux.browser

import com.termux.browser.ai.AiApiRunner
import com.termux.browser.ai.ApiCredentialStore
import com.termux.browser.ai.ApiOutcome
import com.termux.browser.ai.ApiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `api-chat without a runner fails closed`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results
        )
        try {
            val submitted = arbiter.submit(
                BrowserCommand.ApiChat("openai", "Hi.", 100, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("INVALID_REQUEST"))
            assertTrue(host.url == null)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `api-chat via runner completes without touching the page`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val fakeCrypto = object : TokenCrypto {
            override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> =
                ByteArray(12) { 7 } to plain.copyOf()

            override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
                ciphertext.copyOf()
        }
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "tb-apitest-${System.nanoTime()}")
        dir.mkdirs()
        val store = ApiCredentialStore(dir) { fakeCrypto }
        store.setKey("openai", "sk-test")
        val provider = object : ApiProvider {
            override val id = "openai"
            override suspend fun chat(prompt: String, credential: String, maxChars: Int): ApiOutcome {
                assertEquals("sk-test", credential)
                return ApiOutcome.Text("api answer", false)
            }
        }
        val arbiter = CommandArbiter(
            this, ui(), host, controller, policy, results,
            apiRunner = AiApiRunner(store, mapOf("openai" to provider))
        )
        try {
            val submitted = arbiter.submit(
                BrowserCommand.ApiChat("openai", "Hi.", 100, "termux")
            )
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("completed"))
            assertTrue(stored.body.contains("api answer"))
            assertTrue(host.url == null)
        } finally {
            arbiter.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `read parses real webview quoted results`() = runBlocking {
        // On-device evaluateJavascript wraps string results in JSON quotes.
        val host = FakeHost().apply {
            evalResult = "\"{\\\"text\\\":\\\"hello world\\\"}\""
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
            assertTrue(stored!!.body.contains("hello world"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `images scope returns src and alt`() = runBlocking {
        val host = FakeHost().apply {
            evalResult = """{"images":[{"src":"https://example.com/a.png","alt":"icon"},{"src":"blob:xyz"}]}"""
        }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("images", 50000))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("https://example.com/a.png"))
            assertTrue(stored.body.contains("blob:xyz"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `click reports the clicked tag`() = runBlocking {
        val host = FakeHost().apply {
            evalResult = """{"clicked":true,"tag":"BUTTON"}"""
        }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Click("[data-testid]", "termux"))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("completed"))
            assertTrue(stored.body.contains("BUTTON"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `click miss reports without crashing`() = runBlocking {
        val host = FakeHost().apply {
            evalResult = """{"clicked":false,"reason":"not-found"}"""
        }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Click(".nope", "termux"))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("CLICK_MISSED"))
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `tap dispatches through the host`() = runBlocking {
        val host = FakeHost()
        var tapped: Pair<Double, Double>? = null
        val tappingHost = object : PageHost by host {
            override suspend fun tap(xCss: Double, yCss: Double): Boolean {
                tapped = xCss to yCss
                return true
            }
        }
        val results = ResultStore()
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), tappingHost, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Tap(100.0, 200.0, "termux"))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("completed"))
            assertEquals(Pair(100.0, 200.0), tapped)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `tap-control replays a taught control`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "tb-tapctl-${System.nanoTime()}"
        )
        dir.mkdirs()
        try {
            val learned = com.termux.browser.ai.LearnedStore(dir)
            // Taught on the /menu route where the replay runs.
            learned.putControl("example.com", "menu", "button.burger", "menu")
            var tapped: Pair<Double, Double>? = null
            val tappingHost = object : PageHost by host {
                override suspend fun tap(xCss: Double, yCss: Double): Boolean {
                    tapped = xCss to yCss
                    return true
                }
                override suspend fun evalJs(script: String): String? {
                    // Rect probe answers with a 10x10 box at (100,200).
                    return "\"{\\\"x\\\":100,\\\"y\\\":200,\\\"w\\\":10,\\\"h\\\":10}\""
                }
            }
            val ui2 = object : UiRunner {
                override suspend fun <T> run(block: suspend () -> T): T = block()
            }
            val arbiter = CommandArbiter(
                this, ui2, tappingHost, controller, policy, results,
                learnedStore = learned
            )
            try {
                // Host page must match the learned host.
                host.url = "https://example.com/menu"
                val submitted = arbiter.submit(
                    BrowserCommand.TapControl("menu", "termux")
                )
                val id = (submitted as SubmitResult.Accepted).commandId
                val stored = awaitResult(results, id)
                assertTrue(stored!!.body.contains("completed"))
                assertEquals(Pair(105.0, 205.0), tapped)
            } finally {
                arbiter.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tap-control with teaching from another route reports not-found`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "tb-tapctx-${System.nanoTime()}"
        )
        dir.mkdirs()
        try {
            val learned = com.termux.browser.ai.LearnedStore(dir)
            // Legacy host-level teaching only (taught before scoping).
            learned.put("example.com", "div.send")
            var taps = 0
            val tappingHost = object : PageHost by host {
                override suspend fun tap(xCss: Double, yCss: Double): Boolean {
                    taps++
                    return true
                }
                override suspend fun evalJs(script: String): String? = host.evalJs(script)
                override fun currentUrl(): String? = host.url
            }
            val arbiter = CommandArbiter(
                this, ui(), tappingHost, controller, policy, results,
                learnedStore = learned
            )
            try {
                // Different route: the host-level teaching must not serve.
                host.url = "https://example.com/other"
                val submitted = arbiter.submit(
                    BrowserCommand.TapControl("send", "termux")
                )
                val id = (submitted as SubmitResult.Accepted).commandId
                val stored = awaitResult(results, id)
                assertTrue(stored!!.body.contains("SEND_SELECTOR_NOT_FOUND"))
                assertEquals(0, taps)
            } finally {
                arbiter.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tap-control send with stale selector fails validation without tapping`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "tb-tapstale-${System.nanoTime()}"
        )
        dir.mkdirs()
        try {
            val learned = com.termux.browser.ai.LearnedStore(dir)
            // Taught for this exact route, but now matches a toggle.
            learned.putControl("example.com", "send", "div.toggle", "page")
            var taps = 0
            val tappingHost = object : PageHost by host {
                override suspend fun tap(xCss: Double, yCss: Double): Boolean {
                    taps++
                    return true
                }
                override suspend fun evalJs(script: String): String? {
                    if (script.contains("__tbValidate")) {
                        return """{"found":true,"tag":"DIV","role":"","visible":true,"x":10,"y":10,"w":30,"h":30,"anchorFound":false,"matchesAnchor":false}"""
                    }
                    return host.evalJs(script)
                }
                override fun currentUrl(): String? = host.url
            }
            val arbiter = CommandArbiter(
                this, ui(), tappingHost, controller, policy, results,
                learnedStore = learned
            )
            try {
                host.url = "https://example.com/page"
                val submitted = arbiter.submit(
                    BrowserCommand.TapControl("send", "termux")
                )
                val id = (submitted as SubmitResult.Accepted).commandId
                val stored = awaitResult(results, id)
                assertTrue(stored!!.body.contains("SEND_TARGET_VALIDATION_FAILED"))
                assertEquals(0, taps)
                assertNull(learned.getControl("example.com", "send", "page"))
            } finally {
                arbiter.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tap-control without teaching fails closed`() = runBlocking {
        val host = FakeHost()
        val policy = ControlPolicy()
        val results = ResultStore()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "tb-tapctl-${System.nanoTime()}"
        )
        dir.mkdirs()
        try {
            val arbiter = CommandArbiter(
                this, ui(), host, controller, policy, results,
                learnedStore = com.termux.browser.ai.LearnedStore(dir)
            )
            try {
                host.url = "https://example.com/"
                val submitted = arbiter.submit(
                    BrowserCommand.TapControl("menu", "termux")
                )
                val id = (submitted as SubmitResult.Accepted).commandId
                val stored = awaitResult(results, id)
                assertTrue(stored!!.body.contains("LEARNED_CONTROL_MISSING"))
            } finally {
                arbiter.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tap rejects bad coordinates`() {
        assertNull(RequestValidator.checkTap(10.0, 20.0))
        for (bad in listOf(
            Pair(Double.NaN, 1.0), Pair(1.0, Double.POSITIVE_INFINITY),
            Pair(-5.0, 1.0), Pair(1.0, 20000.0)
        )) {
            assertEquals(
                ErrorCodes.INVALID_REQUEST,
                RequestValidator.checkTap(bad.first, bad.second)
            )
        }
    }

    @Test
    fun `html scope returns full markup with truncation flag`() = runBlocking {
        val big = "<html><body>" + "x".repeat(600000) + "</body></html>"
        val inner = "{\"html\":\"" + big.replace("\"", "\\\"") + "\"}"
        val host = FakeHost().apply {
            // Real WebView outer-quotes the script's JSON string result.
            evalResult = "\"" + inner.replace("\"", "\\\"") + "\""
        }
        // Roomier store: a 512 KB payload self-evicts from the default
        // 256 KB store by design (verified elsewhere).
        val results = ResultStore(maxBytes = 2 * 1024 * 1024)
        val controller = BrowserController(host, ControlPolicy(), ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            this, ui(), host, controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Read("html", 1000))
            val id = (submitted as SubmitResult.Accepted).commandId
            val stored = awaitResult(results, id)
            assertTrue(stored!!.body.contains("\"truncated\":true"))
            assertTrue(stored.body.contains("x".repeat(100)))
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
