package com.termux.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Plugin install/list/rollback over real HTTP with a runtime-generated key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class PluginApiTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val token = ByteArray(32) { 5 }
    private val hex = token.joinToString("") { "%02x".format(it) }
    private lateinit var scope: CoroutineScope
    private lateinit var server: LocalApiServer
    private var port = -1

    private class FakeHost : PageHost {
        override fun loadUrl(url: String) {}
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String? = null
        override suspend fun evalJs(script: String): String? = null
    }

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun sha(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun bundleZip(
        id: String = "plugintest",
        version: Int = 1,
        tamper: Boolean = false
    ): ByteArray {
        val selectors = """{"prompt":["textarea"],"submit":["div.send"],"messages":[],"stop":[]}"""
        val health = """{"marker":null,"strategies":["click","enter"]}"""
        val plan = """{"strategies":["click","enter"]}"""
        val files = mapOf(
            "selectors.json" to selectors.toByteArray(),
            "health.json" to health.toByteArray(),
            "plan.json" to plan.toByteArray()
        )
        val filesJson = files.entries.joinToString(",") { (name, bytes) ->
            var hexHash = sha(bytes)
            if (tamper && name == "selectors.json") hexHash = "0".repeat(64)
            "\"$name\":\"$hexHash\""
        }
        val manifest =
            """{"format":"tb-plugin/1","id":"$id","version":$version,"minCore":1,"keyId":"termux-1","hosts":["chat.plugintest.com"],"files":{$filesJson}}"""
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update(manifest.toByteArray())
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files + ("manifest.json" to manifest.toByteArray())) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("signature.sig"))
            zip.write(signer.sign())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

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
        val arbiter = CommandArbiter(scope, ui, host, controller, policy, results)
        val store = PluginStore(folder.newFolder())
        server = LocalApiServer(
            token, ui, arbiter, policy, log, results,
            statusProvider = { StatusBody(state = policy.state.name) },
            pluginStore = store,
            pluginRootKey = keys.public
        )
        port = runBlocking { server.start(0) }
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/v1$path").openConnection()
            as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $hex")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    private fun get(path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/v1$path").openConnection()
            as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $hex")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    private fun installPayload(zip: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(zip)
        return """{"filename":"plugintest.zip","contentBase64":"$b64"}"""
    }

    @Test
    fun `install list and rollback round trip`() {
        val (installCode, installBody) = post("/plugins/install", installPayload(bundleZip()))
        assertEquals(200, installCode)
        assertTrue(installBody.contains("\"version\":1"))
        val (_, listBody) = get("/plugins")
        assertTrue(listBody.contains("plugintest"))

        // Downgrade rejected; same version rejected.
        val (downCode, _) = post("/plugins/install", installPayload(bundleZip(version = 1)))
        assertEquals(400, downCode)

        // Rollback with no previous fails (only one generation ever).
        val (rollbackCode, _) = post("/plugins/rollback", """{"id":"plugintest"}""")
        assertEquals(400, rollbackCode)
    }

    @Test
    fun `tampered bundle rejected`() {
        val (code, body) = post("/plugins/install", installPayload(bundleZip(tamper = true)))
        assertEquals(400, code)
        assertTrue(body.contains("hash mismatch"))
    }

    @Test
    fun `traversal zip rejected`() {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.json"))
            zip.write(ByteArray(10))
            zip.closeEntry()
        }
        val payload = Base64.getEncoder().encodeToString(out.toByteArray())
        val (code, _) = post(
            "/plugins/install",
            """{"filename":"evil.zip","contentBase64":"$payload"}"""
        )
        assertEquals(400, code)
    }
}
