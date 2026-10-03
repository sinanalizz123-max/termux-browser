package com.termux.browser

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val controller = BrowserController(host, policy, log, announce = {})
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ui = object : UiRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        arbiter = CommandArbiter(scope, ui, host, controller, policy, results)
        server = LocalApiServer(
            token, ui, arbiter, policy, log, results,
            statusProvider = { StatusBody(state = policy.state.name) }
        )
        port = runBlocking { server.start() }
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
    fun `websocket rejects missing token`() {
        val socket = java.net.Socket("127.0.0.1", port)
        socket.soTimeout = 10000
        try {
            val out = socket.getOutputStream()
            out.write(
                ("GET /v1/events HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray()
            )
            out.flush()
            val statusLine = socket.getInputStream().bufferedReader().readLine() ?: ""
            assertTrue(statusLine.contains("401"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun `websocket streams command lifecycle`() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            withTimeout(20000) {
                client.webSocket(
                    host = "127.0.0.1",
                    port = port,
                    path = "/v1/events?since=0",
                    request = { header("Authorization", "Bearer $hex") }
                ) {
                    val (_, _) = post(
                        "/commands/open", hex, """{"url":"https://example.com"}"""
                    )
                    val seen = mutableSetOf<String>()
                    val want = setOf("command.queued", "command.started", "command.completed")
                    while (!seen.containsAll(want)) {
                        val frame = incoming.receive()
                        if (frame is Frame.Text) {
                            Regex("\"type\":\"([^\"]+)\"")
                                .find(frame.readText())
                                ?.groupValues?.get(1)?.let { seen.add(it) }
                        }
                    }
                    assertTrue(seen.containsAll(want))
                }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejected urls fail fast`() {
        val (code, body) = post("/commands/open", hex, """{"url":"file:///etc/passwd"}""")
        assertEquals(400, code)
        assertTrue(body.contains(ErrorCodes.INVALID_URL))
    }
}
