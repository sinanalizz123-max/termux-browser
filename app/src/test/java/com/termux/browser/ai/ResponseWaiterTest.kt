package com.termux.browser.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseWaiterTest {

    private class Script(
        val frames: List<Snapshot>,
        val stepMs: Long = 500L,
        var cancelledFlag: Boolean = false,
        var exit: String? = null
    ) {
        var now = 0L
        private var index = 0

        val provider = object : SnapshotProvider {
            override suspend fun snapshot(): Snapshot {
                val frame = frames[index.coerceAtMost(frames.lastIndex)]
                if (index < frames.lastIndex) index++
                now += stepMs
                return frame
            }
        }

        fun waiter(
            softTimeoutMs: Long = 30_000,
            hardTimeoutMs: Long = 180_000
        ) = ResponseWaiter(
            snapshots = provider,
            softTimeoutMs = softTimeoutMs,
            hardTimeoutMs = hardTimeoutMs,
            stableSamples = 3,
            quietPeriodMs = 2_000,
            pollMs = 1,
            clock = { now },
            sleeper = {},
            cancelled = { cancelledFlag },
            exited = { exit }
        )
    }

    private fun snap(
        count: Int = 0,
        text: String = "",
        generating: Boolean = false,
        login: Boolean = false,
        captcha: Boolean = false,
        error: Boolean = false
    ) = Snapshot(count, text, generating, login, captcha, error)

    @Test
    fun `fast response completes`() = runBlocking {
        val script = Script(
            listOf(
                snap(),
                snap(1, "hi", generating = true),
                snap(1, "hi there", generating = true),
                snap(1, "hi there!", generating = false),
                snap(1, "hi there!", generating = false),
                snap(1, "hi there!", generating = false),
                snap(1, "hi there!", generating = false),
                snap(1, "hi there!", generating = false)
            )
        )
        val result = script.waiter().await()
        assertTrue(result is WaitResult.Completed)
        assertEquals("hi there!", (result as WaitResult.Completed).text)
    }

    @Test
    fun `never-stable response hits hard timeout`() = runBlocking {
        val script = Script(
            listOf(snap(), snap(1, "A", true), snap(1, "B", true))
        )
        // Frames alternate: build an alternating tail via provider wrap.
        val alternating = object : SnapshotProvider {
            var tick = 0
            override suspend fun snapshot(): Snapshot {
                script.now += script.stepMs
                tick++
                return if (tick % 2 == 0) snap(1, "A", true) else snap(1, "B", true)
            }
        }
        val waiter = ResponseWaiter(
            snapshots = alternating,
            hardTimeoutMs = 5_000,
            clock = { script.now },
            sleeper = {}
        )
        val result = waiter.await()
        assertEquals(WaitResult.Failed("TIMEOUT"), result)
    }

    @Test
    fun `no appearance hits soft timeout`() = runBlocking {
        val script = Script(listOf(snap()))
        val result = script.waiter(softTimeoutMs = 2_000).await()
        assertEquals(WaitResult.Failed("TIMEOUT"), result)
    }

    @Test
    fun `stale pre-existing message is never completed`() = runBlocking {
        val script = Script(listOf(snap(1, "old answer")))
        val result = script.waiter(softTimeoutMs = 2_000).await()
        assertEquals(WaitResult.Failed("TIMEOUT"), result)
    }

    @Test
    fun `stable but still generating is never completed`() = runBlocking {
        val script = Script(listOf(snap(), snap(1, "done?", generating = true)))
        val result = script.waiter(hardTimeoutMs = 5_000).await()
        assertEquals(WaitResult.Failed("TIMEOUT"), result)
    }

    @Test
    fun `login mid-stream exits`() = runBlocking {
        val script = Script(
            listOf(snap(), snap(1, "hi", true), snap(login = true))
        )
        val result = script.waiter().await()
        assertEquals(WaitResult.Failed("LOGIN_REQUIRED"), result)
    }

    @Test
    fun `cancellation exits`() = runBlocking {
        val script = Script(listOf(snap(), snap(1, "hi", true)))
        script.cancelledFlag = true
        val result = script.waiter().await()
        assertEquals(WaitResult.Failed("CANCELLED"), result)
    }

    @Test
    fun `takeover exit surfaces`() = runBlocking {
        val script = Script(listOf(snap()))
        script.exit = "USER_TAKEOVER"
        val result = script.waiter().await()
        assertEquals(WaitResult.Failed("USER_TAKEOVER"), result)
    }

    @Test
    fun `parser strips evaluateJavascript quoting`() {
        // Real WebView delivers string results JSON-quoted with escaping.
        val quoted =
            "\"{\\\"messageCount\\\":1,\\\"lastText\\\":\\\"hi there\\\",\\\"promptFound\\\":true}\""
        val parsed = SnapshotParser.parse(quoted)!!
        assertEquals(1, parsed.messageCount)
        assertEquals("hi there", parsed.lastText)
        assertTrue(parsed.promptFound)
    }

    @Test
    fun `parser reads composer state`() {
        val busy = SnapshotParser.parse(
            """{"composerKind":"textarea","composerEmpty":false}"""
        )!!
        assertEquals("textarea", busy.composerKind)
        assertEquals(false, busy.composerEmpty)
        val drained = SnapshotParser.parse("""{"composerEmpty":true}""")!!
        assertEquals(true, drained.composerEmpty)
    }

    @Test
    fun `parser handles valid missing and malformed input`() {
        val full = SnapshotParser.parse(
            """{"messageCount":2,"lastText":"hi","generating":true}"""
        )!!
        assertEquals(2, full.messageCount)
        assertEquals("hi", full.lastText)
        assertTrue(full.generating)
        val sparse = SnapshotParser.parse("{}")!!
        assertEquals(0, sparse.messageCount)
        assertEquals("", sparse.lastText)
        assertNull(SnapshotParser.parse(null))
        assertNull(SnapshotParser.parse(""))
        assertNull(SnapshotParser.parse("not json{{{"))
        assertNull(SnapshotParser.parse("[]"))
    }
}
