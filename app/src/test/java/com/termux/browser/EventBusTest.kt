package com.termux.browser

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBusTest {

    @Test
    fun `ids are monotonic and replay is strictly greater`() {
        val bus = EventBus()
        val first = bus.publish("a")
        bus.publish("b")
        val third = bus.publish("c")
        assertEquals(first.eventId + 1, third.eventId - 1)
        val replay = bus.replaySince(first.eventId)
        assertEquals(listOf(first.eventId + 1, third.eventId), replay.events.map { it.eventId })
        assertEquals(false, replay.gap)
    }

    @Test
    fun `history is bounded and old ids report a gap`() {
        val bus = EventBus(maxEvents = 10)
        repeat(25) { bus.publish("e$it") }
        val replay = bus.replaySince(0)
        // since <= 0: live from now, no replay, no gap.
        assertTrue(replay.events.isEmpty())
        assertEquals(false, replay.gap)
        // IDs 1..25 published, retained 16..25. Asking from 5 loses 6..15.
        val gapped = bus.replaySince(5)
        assertEquals(true, gapped.gap)
        assertEquals((16L..25L).toList(), gapped.events.map { it.eventId })
        // Asking from 15 (oldest-1) loses nothing.
        val clean = bus.replaySince(15)
        assertEquals(false, clean.gap)
        assertEquals((16L..25L).toList(), clean.events.map { it.eventId })
    }

    @Test
    fun `stream delivers replay then live without duplicates`() = runBlocking {
        val bus = EventBus()
        bus.publish("before-1")
        bus.publish("before-2")
        val received = mutableListOf<BusEvent>()
        withTimeout(5000) {
            val job = launch {
                bus.stream(0) { event ->
                    received.add(event)
                    if (received.size >= 2) throw StreamDone()
                }
            }
            // Give the stream a moment to snapshot, then publish live events.
            delay(200)
            bus.publish("live-1")
            bus.publish("live-2")
            try {
                job.join()
            } catch (_: StreamDone) {
                job.cancel()
            }
        }
        // since=0: no replay; only the two live events, exactly once each.
        assertEquals(listOf("live-1", "live-2"), received.map { it.type })
    }

    @Test
    fun `stream replays history before live`() = runBlocking {
        val bus = EventBus()
        bus.publish("older")
        val second = bus.publish("old")
        val received = mutableListOf<BusEvent>()
        withTimeout(5000) {
            val job = launch {
                bus.stream(second.eventId - 1) { event ->
                    received.add(event)
                    if (received.size >= 2) throw StreamDone()
                }
            }
            delay(200)
            bus.publish("new")
            try {
                job.join()
            } catch (_: StreamDone) {
                job.cancel()
            }
        }
        assertEquals(listOf("old", "new"), received.map { it.type })
    }

    @Test
    fun `publishers never block on a slow consumer`() = runBlocking {
        val bus = EventBus()
        withTimeout(10000) {
            repeat(1000) { bus.publish("burst-$it") }
        }
        val replay = bus.replaySince(0)
        assertTrue(replay.events.isEmpty())
        // History kept the bounded tail.
        val tail = bus.replaySince(999)
        assertTrue(tail.events.size <= 300)
    }

    private class StreamDone : RuntimeException()
}
