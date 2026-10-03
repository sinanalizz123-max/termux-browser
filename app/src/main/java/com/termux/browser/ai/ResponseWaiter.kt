package com.termux.browser.ai

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * M7 response waiting, fully decoupled from site specifics via
 * SnapshotProvider. Timing is injectable (clock + sleeper) so JVM tests
 * never depend on wall-clock sleeps.
 *
 * Completion requires ALL of:
 * - a response appeared after the baseline (count grew or text changed),
 * - the text unchanged for [stableSamples] consecutive samples,
 * - the quiet period elapsed since the last change,
 * - generating == false (positive non-generating signal),
 * - no exit condition (timeout/cancel/takeover/navigation/login/captcha/error).
 */
@Serializable
data class Snapshot(
    val messageCount: Int = 0,
    val lastText: String = "",
    val generating: Boolean = false,
    val loginRequired: Boolean = false,
    val captcha: Boolean = false,
    val error: Boolean = false,
    val promptFound: Boolean = false,
    val submitFound: Boolean = false
)

interface SnapshotProvider {
    suspend fun snapshot(): Snapshot
}

sealed interface WaitResult {
    data class Completed(val text: String) : WaitResult
    data class Failed(val code: String) : WaitResult
}

object SnapshotParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            fun bool(key: String) =
                obj[key]?.jsonPrimitive?.content == "true"
            Snapshot(
                messageCount = obj["messageCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                lastText = obj["lastText"]?.jsonPrimitive?.content ?: "",
                generating = bool("generating"),
                loginRequired = bool("loginRequired"),
                captcha = bool("captcha"),
                error = bool("error"),
                promptFound = bool("promptFound"),
                submitFound = bool("submitFound")
            )
        }.getOrNull()
    }
}

class ResponseWaiter(
    private val snapshots: SnapshotProvider,
    private val softTimeoutMs: Long = 30_000,
    private val hardTimeoutMs: Long = 180_000,
    private val stableSamples: Int = 3,
    private val quietPeriodMs: Long = 2_000,
    private val pollMs: Long = 500,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val cancelled: () -> Boolean = { false },
    private val exited: () -> String? = { null }
) {
    suspend fun await(): WaitResult {
        val start = clock()
        val baseline = snapshots.snapshot()
        var appeared = false
        var same = 0
        var lastText = baseline.lastText
        var lastChangeAt = start
        while (true) {
            val now = clock()
            if (now - start > hardTimeoutMs) return WaitResult.Failed("TIMEOUT")
            exited()?.let { return WaitResult.Failed(it) }
            if (cancelled()) return WaitResult.Failed("CANCELLED")
            sleeper(pollMs)
            val snap = snapshots.snapshot()
            if (snap.loginRequired) return WaitResult.Failed("LOGIN_REQUIRED")
            if (snap.captcha) return WaitResult.Failed("CAPTCHA_DETECTED")
            if (snap.error) return WaitResult.Failed("AI_ERROR")

            val changed = snap.messageCount > baseline.messageCount ||
                (snap.lastText.isNotBlank() && snap.lastText != baseline.lastText)
            if (changed) appeared = true
            if (!appeared) {
                if (clock() - start > softTimeoutMs) return WaitResult.Failed("TIMEOUT")
                continue
            }
            if (snap.lastText != lastText) {
                lastText = snap.lastText
                lastChangeAt = clock()
                same = 0
                continue
            }
            same++
            if (same >= stableSamples &&
                clock() - lastChangeAt >= quietPeriodMs &&
                !snap.generating
            ) {
                return WaitResult.Completed(lastText)
            }
        }
    }
}
