package com.termux.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.net.HttpURLConnection
import java.net.URL

/**
 * M2 control plane over real localhost HTTP: auth boundary, command
 * acceptance, and result retrieval. No device needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class LocalApiServerTest {

    private class FakeHost : PageHost {
        var url: String? = null
        override fun loadUrl(url: String) {
            this.url = url
        }
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String? = url
        override suspend fun evalJs(script: String): String? = """{"text":"hi"}"""
    }

    private val token = ByteArray(32) { 9 }
    private val hex = token.joinToString("") { "%02x".format(it) }
    private lateinit var scope: CoroutineScope
    private lateinit var arbiter: CommandArbiter
    private lateinit var server: LocalApiServer
    private var port = -1

    @Before
    fun setUp() {
        val policy = ControlPolicy()
        val log = ActivityLog()
        val results = ResultStore()
        val host = FakeHost()
        val controller = BrowserController(host, policy, log) {}
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ui = UiRunner { it() }
        arbiter = CommandArbiter(scope, ui, host, controller, policy, results)
        server = LocalApiServer(token, ui, arbiter, policy, log, results) {
            StatusBody(state = policy.state.name)
        }
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        arbiter.close()
        scope.cancel()
    }

    private fun get(path: String, bearer: String?): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/v1$path").openConnection()
            as HttpURLConnection
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    private fun post(path: String, bearer: String?, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/v1$path").openConnection()
            as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    @Test
    fun `status requires a valid bearer token`() {
        val (unauthorized, _) = get("/status", null)
        assertEquals(401, unauthorized)
        val (wrong, _) = get("/status", "00".repeat(32))
        assertEquals(401, wrong)
        val (ok, body) = get("/status", hex)
        assertEquals(200, ok)
        assertTrue(body.contains("\"state\":\"IDLE\""))
    }

    @Test
    fun `open is accepted and its result is retrievable`() {
        val (code, body) = post("/commands/open", hex, """{"url":"https://example.com"}""")
        assertEquals(200, code)
        val commandId = Regex("\"commandId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        var result = ""
        val deadline = System.currentTimeMillis() + 10000
        while (!result.contains("completed") && System.currentTimeMillis() < deadline) {
            result = get("/result/$commandId", hex).second
            Thread.sleep(100)
        }
        assertTrue(result.contains("completed"))
        assertTrue(result.contains("example.com"))
    }

    @Test
    fun `rejected urls fail fast`() {
        val (code, body) = post("/commands/open", hex, """{"url":"file:///etc/passwd"}""")
        assertEquals(400, code)
        assertTrue(body.contains(ErrorCodes.INVALID_URL))
    }
}
