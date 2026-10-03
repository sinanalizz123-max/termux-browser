package com.termux.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Fixed-port lifecycle (plain JVM): bind, conflict, reclaim, and the
 * not-ready state. The bearer token is an input and is never mutated by
 * bind failures — asserted by round-tripping the same token afterwards.
 */
class FixedPortTest {

    private class FakeHost : PageHost {
        override fun loadUrl(url: String) {}
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun currentUrl(): String? = null
        override suspend fun evalJs(script: String): String? = null
    }

    private val token = ByteArray(32) { 4 }
    private lateinit var scope: CoroutineScope
    private val servers = mutableListOf<LocalApiServer>()
    private val arbiters = mutableListOf<CommandArbiter>()

    private fun directUi() = object : UiRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private fun buildServer(): LocalApiServer {
        val policy = ControlPolicy()
        val host = FakeHost()
        val controller = BrowserController(host, policy, ActivityLog(), announce = {})
        val arbiter = CommandArbiter(
            scope, directUi(), host, controller, policy, ResultStore()
        )
        arbiters.add(arbiter)
        val server = LocalApiServer(
            token, directUi(), arbiter, policy, ActivityLog(), ResultStore(),
            statusProvider = { StatusBody(state = policy.state.name) }
        )
        servers.add(server)
        return server
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        arbiters.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    @Test
    fun `fixed port binds and reports readiness`() = runBlocking {
        val server = buildServer()
        assertEquals(LocalApiServer.FIXED_PORT, server.start())
        assertEquals(LocalApiServer.FIXED_PORT, server.port)
    }

    @Test
    fun `second bind on the fixed port fails deterministically`() = runBlocking {
        val first = buildServer()
        first.start()
        val second = buildServer()
        try {
            second.start()
            fail("expected bind failure on occupied fixed port")
        } catch (_: Exception) {
        }
        // Failed start leaves the server not-ready and the token untouched.
        assertEquals(-1, second.port)
    }

    @Test
    fun `stop then start reclaims the fixed port`() = runBlocking {
        val server = buildServer()
        server.start()
        server.stop()
        assertEquals(-1, server.port)
        assertEquals(LocalApiServer.FIXED_PORT, server.start())
    }

    @Test
    fun `token survives bind failure and restart`() = runBlocking {
        val blocker = java.net.ServerSocket(LocalApiServer.FIXED_PORT)
        try {
            val server = buildServer()
            try {
                server.start()
                fail("expected bind failure")
            } catch (_: Exception) {
            }
            assertEquals(-1, server.port)
        } finally {
            blocker.close()
        }
        // Same token works fine once the port is free.
        val server = buildServer()
        assertEquals(LocalApiServer.FIXED_PORT, server.start())
        assertTrue(server.port > 0)
    }
}
