package com.termux.browser

/**
 * Bounded immutable command results. Retention: bounded count + bytes with a
 * fixed TTL; expired/unknown IDs read as RESULT_NOT_FOUND (404).
 */
data class StoredResult(
    val commandId: String,
    val createdAt: Long,
    val body: String
)

class ResultStore(
    private val maxCount: Int = ProtocolLimits.MAX_RESULTS,
    private val maxBytes: Int = ProtocolLimits.MAX_RESULT_BYTES,
    private val ttlMs: Long = ProtocolLimits.RESULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val results = LinkedHashMap<String, StoredResult>()
    private var bytes = 0

    @Synchronized
    fun put(commandId: String, body: String) {
        evictExpired()
        results[commandId] = StoredResult(commandId, clock(), body)
        bytes += body.length
        while (results.size > maxCount || bytes > maxBytes) {
            val oldest = results.keys.first()
            bytes -= results.remove(oldest)?.body?.length ?: 0
        }
    }

    /** Newest-last snapshot for the debug command listing. */
    @Synchronized
    fun entries(): List<StoredResult> {
        evictExpired()
        return results.values.toList()
    }

    @Synchronized
    fun get(commandId: String): StoredResult? {
        val stored = results[commandId] ?: return null
        if (clock() - stored.createdAt > ttlMs) {
            bytes -= stored.body.length
            results.remove(commandId)
            return null
        }
        return stored
    }

    @Synchronized
    private fun evictExpired() {
        val now = clock()
        val expired = results.values.filter { now - it.createdAt > ttlMs }
        for (stored in expired) {
            bytes -= stored.body.length
            results.remove(stored.commandId)
        }
    }
}
