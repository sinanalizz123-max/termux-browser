package com.termux.browser

/**
 * Bounded in-memory activity log. Defaults persist ACTION + METADATA only;
 * full page/AI content is never stored here (see docs/protocol.md).
 */
data class ActivityEvent(
    val id: Long,
    val timestamp: Long,
    val source: String,
    val action: String,
    val detail: String
)

class ActivityLog(
    private val maxEvents: Int = 300,
    private val maxBytes: Int = 256 * 1024,
    private val maxDetailChars: Int = 2000
) {
    private val events = ArrayDeque<ActivityEvent>()
    private var nextId = 1L
    private var bytes = 0

    @Synchronized
    fun add(source: String, action: String, detail: String): ActivityEvent {
        val trimmed = if (detail.length > maxDetailChars) {
            detail.take(maxDetailChars) + "…[truncated]"
        } else {
            detail
        }
        val event = ActivityEvent(
            id = nextId++,
            timestamp = System.currentTimeMillis(),
            source = source,
            action = action,
            detail = trimmed
        )
        events.addLast(event)
        bytes += trimmed.length
        while (events.size > maxEvents || bytes > maxBytes) {
            val removed = events.removeFirst()
            bytes -= removed.detail.length
        }
        return event
    }

    @Synchronized
    fun since(lastId: Long): List<ActivityEvent> =
        events.filter { it.id > lastId }.toList()
}
