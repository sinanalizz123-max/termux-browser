package com.termux.browser

import com.termux.browser.ai.AdapterRegistry
import com.termux.browser.ai.AiApiRunner
import com.termux.browser.ai.GenericAdapter
import com.termux.browser.ai.LearnedAdapter
import com.termux.browser.ai.LearnedStore
import com.termux.browser.EventTypes
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
    data class Click(val selector: String, val source: String) : BrowserCommand
    data class Tap(val xCss: Double, val yCss: Double, val source: String) : BrowserCommand
    data class TapControl(val control: String, val source: String) : BrowserCommand
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
    private val learnedStore: LearnedStore? = null,
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
                is BrowserCommand.Click -> executeClick(cmd)
                is BrowserCommand.Tap -> executeTap(cmd)
                is BrowserCommand.TapControl -> executeTapControl(cmd)
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
        val detected = registry.detect(pageUrl)
        // Fail closed: unknown page or generic fallback never submits.
        if (detected == null || detected.hosts.isEmpty()) {
            return failed(ErrorCodes.ADAPTER_NOT_FOUND)
        }
        if (cmd.site.isNotBlank() && detected.id != cmd.site) {
            return failed(ErrorCodes.INVALID_REQUEST)
        }
        // Context-scoped learning: only a teaching from THIS page context
        // (e.g. DeepSeek "/a") may drive the submit tap. A teaching from a
        // different route is never trusted here.
        val pageHost = registry.hostOf(pageUrl)
        val pageContext = com.termux.browser.ai.pageContext(pageUrl)
        val learned = learnedStore?.getControl(pageHost, "send", pageContext)
        val adapter = if (!learned.isNullOrBlank()) {
            bus.publish(EventTypes.LEARNED_OVERRIDE, detail = "$pageHost/${pageContext}")
            LearnedAdapter(detected, learned)
        } else {
            detected
        }
        // Health gate on a fresh snapshot: confirmed controls or nothing.
        val probe = ui.run { host.evalJs(adapter.snapshotScript()) }
        val probeSnap = SnapshotParser.parse(probe) ?: Snapshot()
        val baseCount = probeSnap.messageCount
        val health = adapter.health(probeSnap, pageUrl)
        if (health.loginState == "logged_out") return failed(ErrorCodes.LOGIN_REQUIRED)
        if (!health.promptInput || !health.submitControl) {
            return failed(ErrorCodes.HEALTH_CHECK_FAILED)
        }
        val boundedPrompt = cmd.prompt.take(cmd.maxChars.coerceIn(1, ProtocolLimits.MAX_READ_CHARS))
        val selectors = adapter.selectors()
        // Everything from here touches the prompt bridge: native cleanup in
        // finally covers submit failure, waiter outcomes, and cancellation.
        try {
            // A user-taught send control is delivered with a genuine platform
            // tap: script-synthesized clicks/keys are ignored by exactly the
            // controls worth teaching. Otherwise the standard JS path.
            val submitted = if (learned.isNullOrBlank()) {
                submitViaScript(boundedPrompt, selectors)
            } else {
                submitViaLearnedTap(boundedPrompt, selectors, learned, pageHost, pageContext, detected)
            }
            if (submitted != SubmitOutcome.DISPATCHED) return failed(ErrorCodes.SUBMIT_FALSE)

            // Send verification with navigation awareness: the tap may have
            // fired while the page moves to the new chat route. QUIET means
            // nothing happened (fail fast); AMBIGUOUS means a tap probably
            // fired but evidence is inconclusive — never tap again here.
            when (confirmSend(adapter, baseCount, pageUrl)) {
                ConfirmOutcome.CONFIRMED -> Unit
                ConfirmOutcome.QUIET -> return failed(ErrorCodes.SUBMIT_UNCONFIRMED)
                ConfirmOutcome.AMBIGUOUS -> return failed(ErrorCodes.SUBMIT_AMBIGUOUS)
            }

            val startGen = policy.generation
            // Re-anchor the waiter on the post-confirm page: a same-host
            // navigation to the new chat route is normal app flow.
            val startUrl = ui.run { host.currentUrl() } ?: pageUrl
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

    private suspend fun executeClick(cmd: BrowserCommand.Click): String {
        val raw = ui.run {
            host.evalJs(PageScripts.clickScript(json.encodeToString(cmd.selector)))
        }
        val parsed = parseJsObject(raw)
        val clicked = parsed?.get("clicked")?.jsonPrimitive?.content == "true"
        val tag = parsed?.get("tag")?.jsonPrimitive?.content ?: ""
        val reason = parsed?.get("reason")?.jsonPrimitive?.content ?: ""
        return if (clicked) {
            """{"state":"completed","tag":${json.encodeToString(tag)}}"""
        } else {
            """{"state":"failed","error":"${ErrorCodes.CLICK_MISSED}","reason":${json.encodeToString(reason)}}"""
        }
    }

    private suspend fun executeTap(cmd: BrowserCommand.Tap): String {
        if (!cmd.xCss.isFinite() || !cmd.yCss.isFinite()) {
            return failed(ErrorCodes.INVALID_REQUEST)
        }
        val tapped = ui.run { host.tap(cmd.xCss, cmd.yCss) }
        return if (tapped) {
            """{"state":"completed","x":${cmd.xCss},"y":${cmd.yCss}}"""
        } else {
            failed(ErrorCodes.CLICK_MISSED)
        }
    }

    /**
     * Replays a user-taught control with genuine platform input. Prefers
     * the teaching for the current page context; a host-level teaching
     * from another route is reported as not-found for this page instead
     * of being trusted blindly. The send control additionally passes
     * semantic validation — a stale teaching never receives the tap.
     */
    private suspend fun executeTapControl(cmd: BrowserCommand.TapControl): String {
        if (!cmd.control.matches(Regex("[a-z]{1,16}"))) {
            return failed(ErrorCodes.INVALID_REQUEST)
        }
        val store = learnedStore ?: return failed(ErrorCodes.LEARNED_CONTROL_MISSING)
        val pageUrl = ui.run { host.currentUrl() } ?: return failed(ErrorCodes.INVALID_REQUEST)
        val pageHost = registry.hostOf(pageUrl)
        val pageContext = com.termux.browser.ai.pageContext(pageUrl)
        val scoped = store.getControl(pageHost, cmd.control, pageContext)
        val selector = scoped ?: store.getControl(pageHost, cmd.control)
        if (selector == null) return failed(ErrorCodes.LEARNED_CONTROL_MISSING)
        if (scoped == null) return failed(ErrorCodes.SEND_SELECTOR_NOT_FOUND)
        if (cmd.control == "send") {
            val detected = registry.detect(pageUrl)
            val validation = validateSendTarget(selector, detected ?: com.termux.browser.ai.GenericAdapter())
            if (!validation.valid) {
                store.markStale(pageHost, cmd.control, pageContext)
                return failed(ErrorCodes.SEND_TARGET_VALIDATION_FAILED)
            }
            val tapped = tappedAtRect(validation.rectJson)
            return if (tapped) {
                """{"state":"completed","control":${json.encodeToString(cmd.control)}}"""
            } else {
                failed(ErrorCodes.CLICK_MISSED)
            }
        }
        val script = "(function(){try{var e=document.querySelector(" +
            json.encodeToString(selector) +
            ");if(!e)return JSON.stringify(null);var r=e.getBoundingClientRect();" +
            "return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height});}" +
            "catch(_){return JSON.stringify(null);}})()"
        val rect = parseJsObject(ui.run { host.evalJs(script) })
        val x = rect?.get("x")?.jsonPrimitive?.content?.toDoubleOrNull()
        val y = rect?.get("y")?.jsonPrimitive?.content?.toDoubleOrNull()
        val w = rect?.get("w")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val h = rect?.get("h")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        if (x == null || y == null) return failed(ErrorCodes.CLICK_MISSED)
        val tapped = ui.run { host.tap(x + w / 2, y + h / 2) }
        return if (tapped) {
            """{"state":"completed","control":${json.encodeToString(cmd.control)}}"""
        } else {
            failed(ErrorCodes.CLICK_MISSED)
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

    /**
     * Fill with verification: the script reports the composer length it
     * actually sees afterwards. Retried because a hydrating page can drop
     * the first attempt. Returns the verified length (0 = never stuck).
     */
    private suspend fun fillVerified(
        boundedPrompt: String,
        selectors: com.termux.browser.ai.SelectorSet
    ): Int {
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(1000)
            val fill = ui.run {
                host.evalJs(
                    JsPrompt.fillScript(
                        JsPrompt.quotedPrompt(boundedPrompt),
                        JsPrompt.quotedStringList(selectors.prompt)
                    )
                )
            }
            val ack = parseJsObject(fill)
            val filled = ack?.get("filled")?.jsonPrimitive?.content == "true"
            val len = ack?.get("verifyLen")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (filled && len > 0) return len
        }
        return 0
    }

    /** Submit step outcome. Only DISPATCHED means input reached the page. */
    private enum class SubmitOutcome { FAILED, DISPATCHED }

    /** Confirm step outcome. AMBIGUOUS never triggers another tap. */
    private enum class ConfirmOutcome { CONFIRMED, QUIET, AMBIGUOUS }

    private suspend fun submitViaScript(
        boundedPrompt: String,
        selectors: com.termux.browser.ai.SelectorSet
    ): SubmitOutcome {
        if (fillVerified(boundedPrompt, selectors) <= 0) return SubmitOutcome.FAILED
        val submitScript = JsPrompt.submitScript(
            JsPrompt.quotedPrompt(boundedPrompt),
            JsPrompt.quotedStringList(selectors.prompt),
            JsPrompt.quotedStringList(selectors.submit)
        )
        val ack = parseJsObject(ui.run { host.evalJs(submitScript) })
        return if (ack?.get("submitted")?.jsonPrimitive?.content == "true") {
            SubmitOutcome.DISPATCHED
        } else {
            SubmitOutcome.FAILED
        }
    }

    /**
     * Learned submit: fill the composer with the shared script, then tap
     * the taught control with genuine platform input. Returns true only
     * when the tap actually dispatched.
     */
    /**
     * Learned submit: fill the composer, validate the taught selector
     * still resolves to the real send control, then tap it with genuine
     * platform input. A teaching that now matches something else is
     * marked stale and the adapter script path takes over — never tap
     * a control that failed validation.
     */
    private suspend fun submitViaLearnedTap(
        boundedPrompt: String,
        selectors: com.termux.browser.ai.SelectorSet,
        learned: String,
        pageHost: String,
        pageContext: String,
        base: com.termux.browser.ai.SiteAdapter
    ): SubmitOutcome {
        if (fillVerified(boundedPrompt, selectors) <= 0) return SubmitOutcome.FAILED
        // Let the framework re-render settle: filling detaches/replaces DOM
        // nodes, so validation immediately after can hit a dying tree.
        // Only a dispatched tap counts as DISPATCHED, so a tap retry can
        // never double-send. A teaching that fails validation twice is
        // stale (the DOM moved on): mark it and fall back to the adapter
        // script path rather than tapping the wrong control.
        kotlinx.coroutines.delay(2000)
        var validatedOnce = false
        repeat(2) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(1000)
            val validation = validateSendTarget(learned, base)
            if (!validation.valid) return@repeat
            validatedOnce = true
            if (tappedAtRect(validation.rectJson)) return SubmitOutcome.DISPATCHED
        }
        if (!validatedOnce) {
            learnedStore?.markStale(pageHost, "send", pageContext)
            bus.publish(
                EventTypes.COMMAND_FAILED,
                detail = "learned-send-stale:$pageHost/$pageContext"
            )
            return submitViaScript(boundedPrompt, selectors)
        }
        return SubmitOutcome.FAILED
    }

    private data class SendValidation(val valid: Boolean, val rectJson: String?)

    /**
     * Resolves a learned selector to structure (never content) and checks
     * it against the adapter's send anchor. Single round trip: the same
     * response carries the tap rectangle, keeping probe-to-tap tight.
     */
    private suspend fun validateSendTarget(
        learned: String,
        base: com.termux.browser.ai.SiteAdapter
    ): SendValidation {
        val anchor = base.sendAnchor()
        val raw = ui.run {
            host.evalJs(
                JsPrompt.validateSendScript(
                    JsPrompt.quotedPrompt(learned),
                    JsPrompt.quotedPrompt(anchor?.selector ?: ""),
                    anchor?.last == true
                )
            )
        }
        val ack = parseJsObject(raw)
        fun str(key: String) = ack?.get(key)?.jsonPrimitive?.content ?: ""
        fun num(key: String) = str(key).toDoubleOrNull()
        val valid = com.termux.browser.ai.SendTargetValidator.isSendTarget(
            found = str("found") == "true",
            tag = str("tag"),
            role = str("role"),
            visible = str("visible") == "true",
            w = num("w") ?: 0.0,
            h = num("h") ?: 0.0,
            anchorFound = str("anchorFound") == "true",
            matchesAnchor = str("matchesAnchor") == "true"
        )
        if (!valid) return SendValidation(false, null)
        val x = num("x") ?: return SendValidation(false, null)
        val y = num("y") ?: return SendValidation(false, null)
        val w = num("w") ?: 0.0
        val h = num("h") ?: 0.0
        if (w <= 0 || h <= 0) return SendValidation(false, null)
        val rect = """{"x":$x,"y":$y,"w":$w,"h":$h}"""
        return SendValidation(true, rect)
    }

    private suspend fun tappedAtRect(rectRaw: String?): Boolean {
        val rect = parseJsObject(rectRaw) ?: return false
        fun num(key: String) = rect[key]?.jsonPrimitive?.content?.toDoubleOrNull()
        val x = num("x") ?: return false
        val y = num("y") ?: return false
        val w = num("w") ?: 0.0
        val h = num("h") ?: 0.0
        if (w <= 0 || h <= 0) return false
        return ui.run { host.tap(x + w / 2, y + h / 2) }
    }

    private fun failed(code: String): String =
        """{"state":"failed","error":"$code"}"""

    /**
     * Confirms the prompt actually left, by the first signal that fires:
     * a new message above the probe baseline, a drained composer, or a
     * same-host navigation (sites navigate to the new chat on send).
     * Bounded (~10s); never blocks the response waiter on an unconfirmed
     * send. Message counters alone are unreliable: some sites' selectors
     * match static decoration rather than messages.
     */
    /**
     * Navigation-aware send confirmation, bounded at ~15s. A same-host
     * route change (e.g. DeepSeek landing on /a/chat/s/<id>) is treated
     * as possible evidence of a send: polling continues on the new page
     * instead of failing. At timeout, an observed same-host navigation
     * yields AMBIGUOUS — the tap may have fired, so the caller must NOT
     * tap again. Bare count movement alone is not evidence (snapshot
     * noise); only total quiet without navigation is QUIET.
     */
    private suspend fun confirmSend(
        adapter: com.termux.browser.ai.SiteAdapter,
        baseCount: Int,
        startUrl: String
    ): ConfirmOutcome {
        val startHost = registry.hostOf(startUrl)
        val startPath = UrlPolicy.pathOf(startUrl)
        var navigated = false
        repeat(30) {
            val snap = SnapshotParser.parse(ui.run { host.evalJs(adapter.snapshotScript()) })
                ?: Snapshot()
            if (snap.messageCount > baseCount || snap.composerEmpty) return ConfirmOutcome.CONFIRMED
            val now = ui.run { host.currentUrl() }
            if (now != null && registry.hostOf(now) == startHost &&
                UrlPolicy.pathOf(now) != startPath
            ) {
                navigated = true
            }
            kotlinx.coroutines.delay(500)
        }
        return if (navigated) ConfirmOutcome.AMBIGUOUS else ConfirmOutcome.QUIET
    }

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
            "images" -> ReadResult(
                scope = cmd.scope,
                images = parsed?.get("images")?.jsonArray?.take(200)?.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        ImageResult(
                            src = (obj["src"]?.jsonPrimitive?.content ?: "").take(2000),
                            alt = (obj["alt"]?.jsonPrimitive?.content ?: "").take(200)
                        )
                    }.getOrNull()
                } ?: emptyList()
            )
            "html" -> {
                val html = parsed?.get("html")?.jsonPrimitive?.content ?: ""
                val cap = ProtocolLimits.MAX_HTML_CHARS
                ReadResult(
                    scope = cmd.scope,
                    text = html.take(cap),
                    truncated = html.length > cap
                )
            }
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
