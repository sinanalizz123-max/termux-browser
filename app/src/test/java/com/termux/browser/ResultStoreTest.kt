package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultStoreTest {

    @Test
    fun `results round-trip by id`() {
        val store = ResultStore()
        store.put("cmd-1", """{"state":"completed"}""")
        assertEquals("""{"state":"completed"}""", store.get("cmd-1")?.body)
        assertNull(store.get("cmd-unknown"))
    }

    @Test
    fun `expired results read as missing`() {
        var now = 0L
        val store = ResultStore(ttlMs = 1000, clock = { now })
        store.put("cmd-1", "x")
        now += 1001
        assertNull(store.get("cmd-1"))
    }

    @Test
    fun `store is bounded by count`() {
        val store = ResultStore(maxCount = 3, maxBytes = 1_000_000)
        repeat(5) { store.put("cmd-$it", "body-$it") }
        assertNull(store.get("cmd-0"))
        assertNull(store.get("cmd-1"))
        assertTrue(store.get("cmd-4") != null)
    }
}
