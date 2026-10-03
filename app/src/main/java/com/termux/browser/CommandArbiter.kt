package com.termux.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * M2 command plane. Single owner of automation work: HTTP handlers validate
 * and enqueue, the worker executes serially through the BrowserController.
 * The queue is bounded — a full queue answers QUEUE_FULL (HTTP 429) instead
 * of accumulating arbitrary work.
 */
sealed interface BrowserCommand {
    data class Open(val input: String, val source: String) : BrowserCommand
    data class Navigate(val kind: Kind, val source: String) : BrowserCommand {
        enum class Kind { BACK, FORWARD, RELOAD }
    }
    data class Read(val scope: String, val maxChars: Int) : BrowserCommand
}

sealed interface SubmitResult {
    data class Accepted(val commandId: String, val generationId: Int) : SubmitResult
    data object QueueFull : SubmitResult
}

interface UiRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class CommandArbiter(
    scope: CoroutineScope,
    private val ui: UiRunner,
    private val host: PageHost,
    private val controller: BrowserController,
    private val policy: ControlPolicy,
    private val results: ResultStore,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    data class QueuedCommand(
        val commandId: String,
        val generationId: Int,
        val command: BrowserCommand
    )

    private val channel = Channel<QueuedCommand>(ProtocolLimits.MAX_QUEUE)
    private val ids = AtomicLong(0)
    private val pending = AtomicInteger(0)
    private val worker = scope.launch {
        for (queued in channel) {
            try {
                execute(queued)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun submit(command: BrowserCommand): SubmitResult {
        val id = "cmd-" + ids.incrementAndGet()
        val queued = QueuedCommand(id, policy.generation, command)
        if (!channel.trySend(queued).isSuccess) return SubmitResult.QueueFull
        pending.incrementAndGet()
        return SubmitResult.Accepted(id, queued.generationId)
    }

    fun pendingCount(): Int = pending.get()

    /** Stop: queued work completes as CANCELLED, live page stops. */
    suspend fun stopNow(source: String) {
        var drained = channel.tryReceive()
        while (drained.isSuccess) {
            val queued = drained.getOrThrow()
            results.put(
                queued.commandId,
                """{"state":"cancelled","commandId":"${queued.commandId}"}"""
            )
            pending.decrementAndGet()
            drained = channel.tryReceive()
        }
        ui.run { controller.stop(source) }
    }

    fun close() {
        worker.cancel()
        channel.close()
    }

    private suspend fun execute(queued: QueuedCommand) {
        // Explicit resume gate: never run automation while paused/takeover.
        while (policy.state == ControlState.PAUSED ||
            policy.state == ControlState.USER_TAKEOVER
        ) {
            delay(100)
        }
        val payload = try {
            when (val cmd = queued.command) {
                is BrowserCommand.Open -> executeOpen(cmd)
                is BrowserCommand.Navigate -> executeNavigate(cmd)
                is BrowserCommand.Read -> executeRead(cmd)
            }
        } catch (e: Exception) {
            """{"state":"failed","commandId":"${queued.commandId}","error":"${e.javaClass.simpleName}"}"""
        }
        results.put(queued.commandId, payload)
    }

    private suspend fun executeOpen(cmd: BrowserCommand.Open): String {
        val url = UrlPolicy.normalize(cmd.input)
        if (url == null) {
            ui.run { controller.open(cmd.input, cmd.source) }
            return """{"state":"failed","error":"${ErrorCodes.INVALID_URL}"}"""
        }
        ui.run { controller.open(cmd.input, cmd.source) }
        return """{"state":"completed","url":${json.encodeToString(url)}}"""
    }

    private suspend fun executeNavigate(cmd: BrowserCommand.Navigate): String {
        ui.run {
            when (cmd.kind) {
                BrowserCommand.Navigate.Kind.BACK -> controller.back(cmd.source)
                BrowserCommand.Navigate.Kind.FORWARD -> controller.forward(cmd.source)
                BrowserCommand.Navigate.Kind.RELOAD -> controller.reload(cmd.source)
            }
        }
        return """{"state":"completed"}"""
    }

    private suspend fun executeRead(cmd: BrowserCommand.Read): String {
        val raw = ui.run { host.evalJs(PageScripts.forScope(cmd.scope)) }
        val parsed = raw?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val result = when (cmd.scope) {
            "title" -> ReadResult(
                scope = cmd.scope,
                text = parsed?.get("title")?.jsonPrimitive?.content?.take(cmd.maxChars)
            )
            "links" -> ReadResult(
                scope = cmd.scope,
                links = parsed?.get("links")?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        LinkResult(
                            text = (obj["text"]?.jsonPrimitive?.content ?: "").take(200),
                            href = (obj["href"]?.jsonPrimitive?.content ?: "").take(2000),
                            visible = obj["visible"]?.jsonPrimitive?.content == "true"
                        )
                    }.getOrNull()
                } ?: emptyList()
            )
            else -> {
                val text = parsed?.get("text")?.jsonPrimitive?.content ?: ""
                ReadResult(
                    scope = cmd.scope,
                    text = text.take(cmd.maxChars),
                    truncated = text.length > cmd.maxChars
                )
            }
        }
        return json.encodeToString(result)
    }
}
