package com.termux.browser

/**
 * Full-text recording buffer for Termux debugging. OFF by default: nothing
 * is captured until recording is enabled via the control API, and it can be
 * stopped the same way. Crash forensics bypass this gate (a crash must
 * never be missed because recording was off).
 */
object Recorder {
    data class RecordEntry(
        val id: Long,
        val timestamp: Long,
        val kind: String,
        val text: String
    )

    private const val MAX_ENTRIES = 500
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_TEXT = 2000

    @Volatile
    var enabled: Boolean = false

    private val lock = Any()
    private var nextId = 1L
    private var bytes = 0
    private val entries = ArrayDeque<RecordEntry>()

    fun record(kind: String, text: String) {
        if (!enabled) return
        val trimmed = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…[truncated]" else text
        synchronized(lock) {
            if (!enabled) return
            entries.addLast(RecordEntry(nextId++, System.currentTimeMillis(), kind, trimmed))
            bytes += trimmed.length
            while (entries.size > MAX_ENTRIES || bytes > MAX_BYTES) {
                val removed = entries.removeFirst()
                bytes -= removed.text.length
            }
        }
    }

    fun since(lastId: Long): List<RecordEntry> = synchronized(lock) {
        entries.filter { it.id > lastId }.toList()
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0
    }
}
