package com.termux.browser

import com.termux.browser.ai.AdapterRegistry
import com.termux.browser.ai.AiApiRunner
import com.termux.browser.ai.GenericAdapter
import com.termux.browser.ai.JsPrompt
import com.termux.browser.ai.ResponseWaiter
import com.termux.browser.ai.Snapshot
import com.termux.browser.ai.SnapshotParser
import com.termux.browser.ai.SnapshotProvider
import com.termux.browser.ai.WaitResult
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
    data class AiChat(
        val site: String,
        val prompt: String,
        val maxChars: Int,
        val source: String
    ) : BrowserCommand
    data class ApiChat(
        val provider: String,
        val prompt: String,
        val maxChars: Int,
        val source: String
    ) : BrowserCommand
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
    private val bus: EventBus = EventBus(),
    private val registry: AdapterRegistry = AdapterRegistry(listOf(GenericAdapter())),
    private val apiRunner: AiApiRunner? = null,
    private val onWorkChanged: (Boolean) -> Unit = {},
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
                if (pending.decrementAndGet() == 0) onWorkChanged(false)
            }
        }
    }

    fun submit(command: BrowserCommand): SubmitResult {
        val id = "cmd-" + ids.incrementAndGet()
        val queued = QueuedCommand(id, policy.generation, command)
        if (!channel.trySend(queued).isSuccess) return SubmitResult.QueueFull
        pending.incrementAndGet()
        bus.publish(EventTypes.COMMAND_QUEUED, commandId = id, state = "queued")
        onWorkChanged(true)
        return SubmitResult.Accepted(id, queued.generationId)
    }

    fun pendingCount(): Int = pending.get()

    /** Debug-only: single static eval outside the command queue. */
    suspend fun debugEval(script: String): String? = ui.run { host.evalJs(script) }

    /** Stop: queued work completes as CANCELLED, live page stops. */
    suspend fun stopNow(source: String) {
        var drained = channel.tryReceive()
        while (drained.isSuccess) {
            val queued = drained.getOrThrow()
            results.put(
                queued.commandId,
                """{"state":"cancelled","commandId":"${queued.commandId}"}"""
            )
            bus.publish(EventTypes.COMMAND_CANCELLED, commandId = queued.commandId)
            pending.decrementAndGet()
            drained = channel.tryReceive()
        }
        ui.run { controller.stop(source) }
        if (pending.get() == 0) onWorkChanged(false)
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
        bus.publish(EventTypes.COMMAND_STARTED, commandId = queued.commandId)
        val payload = try {
            when (val cmd = queued.command) {
                is BrowserCommand.Open -> executeOpen(cmd)
                is BrowserCommand.Navigate -> executeNavigate(cmd)
                is BrowserCommand.Read -> executeRead(cmd)
                is BrowserCommand.AiChat -> executeAiChat(cmd)
                is BrowserCommand.ApiChat -> executeApiChat(cmd)
            }
        } catch (e: Exception) {
            """{"state":"failed","commandId":"${queued.commandId}","error":"${e.javaClass.simpleName}"}"""
        }
        // Result-commit gate: work that began under generation N must not
        // publish after the policy advanced past N, even if cancellation
        // raced with completion.
        val (final, doneType) = if (queued.generationId != policy.generation) {
            Pair(
                """{"state":"cancelled","commandId":"${queued.commandId}"}""",
                EventTypes.COMMAND_CANCELLED
            )
        } else if (payload.contains("\"state\":\"failed\"")) {
            Pair(payload, EventTypes.COMMAND_FAILED)
        } else {
            Pair(payload, EventTypes.COMMAND_COMPLETED)
        }
        results.put(queued.commandId, final)
        bus.publish(doneType, commandId = queued.commandId)
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

    private suspend fun executeAiChat(cmd: BrowserCommand.AiChat): String {
        // Explicit prompt-size limit before anything touches the page.
        if (cmd.prompt.length > ProtocolLimits.MAX_PROMPT_CHARS) {
            return failed(ErrorCodes.INVALID_REQUEST)
        }
        val pageUrl = ui.run { host.currentUrl() }
            ?: return failed(ErrorCodes.INVALID_REQUEST)
        val adapter = registry.detect(pageUrl)
        // Fail closed: unknown page or generic fallback never submits.
        if (adapter == null || adapter.hosts.isEmpty()) {
            return failed(ErrorCodes.ADAPTER_UNRECOGNIZED)
        }
        if (cmd.site.isNotBlank() && adapter.id != cmd.site) {
            return failed(ErrorCodes.INVALID_REQUEST)
        }
        // Health gate on a fresh snapshot: confirmed controls or nothing.
        val probe = ui.run { host.evalJs(adapter.snapshotScript()) }
        val health = adapter.health(SnapshotParser.parse(probe) ?: Snapshot(), pageUrl)
        if (health.loginState == "logged_out") return failed(ErrorCodes.LOGIN_REQUIRED)
        if (!health.promptInput || !health.submitControl) {
            return failed(ErrorCodes.ADAPTER_UNRECOGNIZED)
        }
        val boundedPrompt = cmd.prompt.take(cmd.maxChars.coerceIn(1, ProtocolLimits.MAX_READ_CHARS))
        val selectors = adapter.selectors()
        val submitScript = JsPrompt.submitScript(
            JsPrompt.quotedPrompt(boundedPrompt),
            JsPrompt.quotedStringList(selectors.prompt),
            JsPrompt.quotedStringList(selectors.submit)
        )
        // Everything from here touches the prompt bridge: native cleanup in
        // finally covers submit failure, waiter outcomes, and cancellation.
        try {
            val ackRaw = ui.run { host.evalJs(submitScript) }
            val ack = parseJsObject(ackRaw)
            val submitted = ack?.get("submitted")?.jsonPrimitive?.content == "true"
            if (!submitted) return failed(ErrorCodes.ADAPTER_UNRECOGNIZED)

            val startGen = policy.generation
            val startUrl = pageUrl
            val provider = object : SnapshotProvider {
                override suspend fun snapshot(): Snapshot {
                    val raw = ui.run { host.evalJs(adapter.snapshotScript()) }
                    return SnapshotParser.parse(raw) ?: Snapshot()
                }
            }
            // Same-host path changes after submit are normal app flow
            // (e.g. ChatGPT navigating to /c/<id> for the new chat). Only
            // a host change means the user actually went elsewhere.
            val startHost = registry.hostOf(startUrl)
            val outcome = ResponseWaiter(
                snapshots = provider,
                exited = {
                    ui.run {
                        when {
                            policy.state == ControlState.STOPPED -> "CANCELLED"
                            policy.generation != startGen -> "USER_TAKEOVER"
                            registry.hostOf(host.currentUrl() ?: "") != startHost -> "NAVIGATION_CHANGED"
                            else -> null
                        }
                    }
                }
            ).await()
            return when (outcome) {
                is WaitResult.Completed -> {
                    val text = outcome.text.take(cmd.maxChars)
                    val truncated = outcome.text.length > cmd.maxChars
                    """{"state":"completed","text":${json.encodeToString(text)},"truncated":$truncated}"""
                }
                is WaitResult.Failed -> failed(outcome.code)
            }
        } finally {
            ui.run { runCatching { host.evalJs(JsPrompt.CLEAR_SCRIPT) } }
        }
    }

    private suspend fun executeApiChat(cmd: BrowserCommand.ApiChat): String {
        if (cmd.prompt.length > ProtocolLimits.MAX_PROMPT_CHARS) {
            return failed(ErrorCodes.INVALID_REQUEST)
        }
        // No WebView is touched on this path by construction.
        return apiRunner?.chat(cmd.provider, cmd.prompt, cmd.maxChars)
            ?: failed(ErrorCodes.INVALID_REQUEST)
    }

    private fun failed(code: String): String =
        """{"state":"failed","error":"$code"}"""

    /**
     * evaluateJavascript delivers string results JSON-quoted (outer quotes
     * plus escaping). Strip one quoting layer before parsing, so on-device
     * behavior matches the direct-JSON fakes used in tests.
     */
    private fun parseJsObject(raw: String?): kotlinx.serialization.json.JsonObject? {
        if (raw == null) return null
        val unquoted = runCatching {
            json.decodeFromString<String>(raw)
        }.getOrNull() ?: raw
        return runCatching { json.parseToJsonElement(unquoted).jsonObject }.getOrNull()
    }

    private suspend fun executeRead(cmd: BrowserCommand.Read): String {
        val raw = ui.run { host.evalJs(PageScripts.forScope(cmd.scope)) }
        val parsed = parseJsObject(raw)
        val result = when (cmd.scope) {
            "title" -> ReadResult(
                scope = cmd.scope,
                text = parsed?.get("title")?.jsonPrimitive?.content?.take(cmd.maxChars)
            )
            "links" -> ReadResult(
                scope = cmd.scope,
                links = parsed?.get("links")?.jsonArray?.take(500)?.mapNotNull { element ->
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
