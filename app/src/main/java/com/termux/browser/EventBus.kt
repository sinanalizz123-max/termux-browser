package com.termux.browser

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * M5 typed event bus. Single global event order via monotonic IDs.
 *
 * Replay semantics (explicit, no silent gaps):
 * - since=N replays events with IDs strictly greater than N.
 * - If N predates retained history, the stream starts with a history_gap
 *   event so the client resynchronizes instead of seeing an incomplete stream.
 * - Live delivery follows replay with no gap or duplicate: the live
 *   subscription starts BEFORE the history snapshot, and replayed IDs
 *   deduplicate buffered live events.
 *
 * Publishers never block: history update holds only a short lock, live
 * fan-out is tryEmit into a DROP_OLDEST buffer, slow consumers drop oldest.
 */
@Serializable
data class BusEvent(
    val eventId: Long,
    val timestamp: Long,
    val type: String,
    val commandId: String? = null,
    val state: String? = null,
    val detail: String = ""
)

object EventTypes {
    const val COMMAND_QUEUED = "command.queued"
    const val COMMAND_STARTED = "command.started"
    const val COMMAND_COMPLETED = "command.completed"
    const val COMMAND_FAILED = "command.failed"
    const val COMMAND_CANCELLED = "command.cancelled"
    const val AUTOMATION_PAUSED = "automation.paused"
    const val AUTOMATION_RESUMED = "automation.resumed"
    const val AUTOMATION_STOPPED = "automation.stopped"
    const val USER_TAKEOVER = "user.takeover"
    const val BROWSER_URL_CHANGED = "browser.url_changed"
    const val BROWSER_TITLE_CHANGED = "browser.title_changed"
    const val BROWSER_LOADING_STARTED = "browser.loading_started"
    const val BROWSER_LOADING_FINISHED = "browser.loading_finished"
    const val BROWSER_ERROR = "browser.error"
    const val HISTORY_GAP = "history_gap"
    const val LEARNED_OVERRIDE = "learned.override"
}

class EventBus(
    private val maxEvents: Int = 300,
    private val maxBytes: Int = 256 * 1024,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var nextId = 1L
    private val history = ArrayDeque<BusEvent>()
    private var bytes = 0

    private val mutableLive = MutableSharedFlow<BusEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val live = mutableLive.asSharedFlow()

    /** Non-blocking: short lock for history, tryEmit for fan-out. */
    fun publish(
        type: String,
        commandId: String? = null,
        state: String? = null,
        detail: String = ""
    ): BusEvent {
        val event = synchronized(lock) {
            val created = BusEvent(
                eventId = nextId++,
                timestamp = clock(),
                type = type,
                commandId = commandId,
                state = state,
                detail = if (detail.length > 2000) detail.take(2000) + "…[truncated]" else detail
            )
            history.addLast(created)
            bytes += created.detail.length
            while (history.size > maxEvents || bytes > maxBytes) {
                val removed = history.removeFirst()
                bytes -= removed.detail.length
            }
            created
        }
        mutableLive.tryEmit(event)
        Recorder.record(
            "event",
            "${event.type} ${event.commandId ?: ""} ${event.state ?: ""}".trim()
        )
        return event
    }

    data class Replay(val events: List<BusEvent>, val gap: Boolean)

    fun replaySince(since: Long): Replay {
        synchronized(lock) {
            // since <= 0: live from now, no replay and no gap.
            if (history.isEmpty() || since <= 0 || since >= history.last().eventId) {
                return Replay(emptyList(), gap = false)
            }
            val oldest = history.first().eventId
            return Replay(
                history.filter { it.eventId > since }.toList(),
                gap = since < oldest - 1
            )
        }
    }

    /**
     * Gap-free stream: subscribe to live first, snapshot history second,
     * dedupe by ID. Runs until the caller's scope is cancelled.
     */
    suspend fun stream(since: Long, send: suspend (BusEvent) -> Unit) {
        coroutineScope {
            val pending = Channel<BusEvent>(Channel.UNLIMITED)
            val collector = launch { live.collect { pending.trySend(it) } }
            try {
                var lastId = since
                val replay = replaySince(since)
                if (replay.gap) {
                    val gapEvent = BusEvent(
                        eventId = -1,
                        timestamp = clock(),
                        type = EventTypes.HISTORY_GAP,
                        detail = "history moved past requested id"
                    )
                    send(gapEvent)
                }
                for (event in replay.events) {
                    send(event)
                    lastId = event.eventId
                }
                for (event in pending) {
                    if (event.eventId > lastId) {
                        send(event)
                        lastId = event.eventId
                    }
                }
            } finally {
                collector.cancel()
                pending.close()
            }
        }
    }
}
