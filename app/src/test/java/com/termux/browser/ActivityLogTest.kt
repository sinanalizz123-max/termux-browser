package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogTest {

    @Test
    fun `events are replayable by id`() {
        val log = ActivityLog()
        log.add("user", "open", "https://a.example")
        val second = log.add("system", "page_ready", "https://a.example")
        val replay = log.since(1)
        assertEquals(1, replay.size)
        assertEquals(second.id, replay[0].id)
    }

    @Test
    fun `buffer is bounded by count and bytes`() {
        val log = ActivityLog(maxEvents = 5, maxBytes = 100)
        repeat(20) { log.add("user", "open", "https://example.com/$it") }
        val all = log.since(0)
        assertTrue(all.size <= 5)
    }

    @Test
    fun `long details are truncated`() {
        val log = ActivityLog(maxDetailChars = 10)
        val event = log.add("user", "read", "x".repeat(5000))
        assertTrue(event.detail.length < 5000)
        assertTrue(event.detail.contains("truncated"))
    }
}
