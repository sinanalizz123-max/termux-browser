package com.termux.browser

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import com.termux.browser.ai.PluginAdapter
import com.termux.browser.ai.PluginBundle
import com.termux.browser.ai.PluginCrypto
import com.termux.browser.ai.ValidBundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * M2 control plane. Ktor CIO bound to 127.0.0.1 on an ephemeral port.
 * Every mutating call is validated, enqueued with a command ID, and executed
 * serially by the CommandArbiter. ai-chat is reserved, not implemented.
 */
class RateLimiter(
    private val max: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var windowStart = 0L
    private var count = 0

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now - windowStart >= windowMs) {
            windowStart = now
            count = 0
        }
        return if (count < max) {
            count++
            true
        } else {
            false
        }
    }
}

class LocalApiServer(
    private val token: ByteArray,
    private val ui: UiRunner,
    private val arbiter: CommandArbiter,
    private val policy: ControlPolicy,
    private val log: ActivityLog,
    private val results: ResultStore,
    private val statusProvider: suspend () -> StatusBody,
    private val bus: EventBus = EventBus(),
    private val debugLog: ActivityLog = ActivityLog(),
    private val reportProvider: suspend () -> DebugReport = { DebugReport() },
    private val crashRoot: java.io.File? = null,
    private val pluginStore: PluginStore? = null,
    private val pluginRootKey: java.security.PublicKey? = null,
    private val onPluginsChanged: () -> Unit = {},
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // Explicit: default-valued fields must still serialize. Never rely
        // on the implicit default here after observing dropped fields.
        encodeDefaults = true
    }
) {
    private var engine: EmbeddedServer<*, *>? = null
    var port: Int = -1
        private set

    companion object {
        /** Stable pair-once port. Localhost only; the token is the auth. */
        const val FIXED_PORT = 8765
    }

    private val commandLimiter = RateLimiter(max = 60, windowMs = 60_000)

    /**
     * Pair-once model: the server always binds the same fixed localhost
     * port, and the bearer token persists in app storage. Termux pairs a
     * single time; app restarts never require re-pairing — just reopen.
     */
    suspend fun start(preferredPort: Int = FIXED_PORT): Int {
        val server = embeddedServer(
            factory = CIO,
            host = "127.0.0.1",
            port = preferredPort,
            module = {
                install(ContentNegotiation) { json(json) }
                install(WebSockets)
                routing {
                    route("/v1") {
                        get("/status") { handleStatus() }
                        get("/activity") { handleActivity() }
                        get("/result/{id}") { handleResult() }
                        post("/commands/open") { handleOpen() }
                        post("/commands/search") { handleSearch() }
                        post("/commands/back") { handleNavigate(BrowserCommand.Navigate.Kind.BACK) }
                        post("/commands/forward") { handleNavigate(BrowserCommand.Navigate.Kind.FORWARD) }
                        post("/commands/reload") { handleNavigate(BrowserCommand.Navigate.Kind.RELOAD) }
                        post("/commands/stop") { handleStopCommand() }
                    post("/commands/read") { handleRead() }
                    post("/commands/ai-chat") { handleAiChat() }
                    post("/commands/api-chat") { handleApiChat() }
                    post("/control/pause") { handlePause() }
                    post("/control/resume") { handleResume() }
                    post("/control/stop") { handleControlStop() }
                    post("/plugins/install") { handlePluginInstall() }
                    post("/plugins/rollback") { handlePluginRollback() }
                    get("/plugins") { handlePluginList() }
                    webSocket("/events") { handleEvents() }
                    get("/debug/console") { handleDebugConsole() }
                    get("/debug/report") { handleDebugReport() }
                    post("/debug/probe") { handleDebugProbe() }
                    get("/debug/events") { handleDebugEvents() }
                    get("/debug/commands") { handleDebugCommands() }
                    get("/debug/crashes") { handleDebugCrashes() }
                    get("/debug/crashes/{name}") { handleDebugCrashFile() }
                    get("/debug/recording") { handleDebugRecording() }
                    post("/control/recording") { handleRecordingToggle() }
                }
            }
            }
        ).start(wait = false)
        engine = server
        port = server.engine.resolvedConnectors().first().port
        log.add("system", "api_started", "127.0.0.1:$port")
        return port
    }

    fun stop() {
        runCatching { engine?.stop(1000, 2000) }
        engine = null
        port = -1
    }

    private fun RoutingContext.requestId(): String =
        call.request.header("X-Request-Id")?.take(64) ?: ("req-" + UUID.randomUUID())

    private fun RoutingContext.envelope(commandId: String? = null): Envelope = Envelope(
        requestId = requestId(),
        commandId = commandId,
        generationId = policy.generation
    )

    private suspend fun RoutingContext.authorized(): Boolean {
        if (!RequestValidator.checkBearer(call.request.header("Authorization"), token)) {
            val body = ApiErrorBody(
                envelope = envelope(),
                error = ErrorDetail(ErrorCodes.AUTH_REQUIRED, "Missing or invalid bearer token.")
            )
            call.respondText(
                json.encodeToString(body),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
            return false
        }
        return true
    }

    private suspend fun RoutingContext.readJsonBody(): String? {
        if (call.request.header("Content-Type")?.startsWith("application/json") != true) {
            error(ErrorCodes.INVALID_REQUEST, "Content-Type must be application/json.")
            return null
        }
        val text = runCatching { call.receiveText() }.getOrNull()
        if (text == null) {
            error(ErrorCodes.INVALID_REQUEST, "Unreadable request body.")
            return null
        }
        RequestValidator.checkBodySize(text.toByteArray(Charsets.UTF_8).size)?.let { code ->
            error(code, "Request body too large.")
            return null
        }
        return text
    }

    private suspend fun RoutingContext.error(code: String, message: String) {
        val body = ApiErrorBody(envelope(), ErrorDetail(code, message))
        call.respondText(
            json.encodeToString(body),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
    }

    private suspend fun RoutingContext.rateLimited(): Boolean {
        if (commandLimiter.tryAcquire()) return false
        val body = ApiErrorBody(
            envelope(),
            ErrorDetail(ErrorCodes.RATE_LIMITED, "Too many requests.", recoverable = true)
        )
        call.respondText(
            json.encodeToString(body),
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests
        )
        return true
    }

    private suspend fun RoutingContext.accepted(accepted: SubmitResult.Accepted) {
        val body = AcceptedBody(envelope(accepted.commandId))
        call.respondText(json.encodeToString(body), ContentType.Application.Json)
    }

    private suspend fun RoutingContext.queueFull() {
        val body = ApiErrorBody(
            envelope(),
            ErrorDetail(ErrorCodes.QUEUE_FULL, "Command queue is full.", recoverable = true)
        )
        call.respondText(
            json.encodeToString(body),
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests
        )
    }

    private suspend fun RoutingContext.handleStatus() {
        if (!authorized()) return
        val current = ui.run { statusProvider() }
        val body = current.copy(envelope = envelope())
        call.respondText(json.encodeToString(body), ContentType.Application.Json)
    }

    private suspend fun RoutingContext.handleActivity() {
        if (!authorized()) return
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val events = log.since(since)
        val items = events.joinToString(",") {
            """{"id":${it.id},"timestamp":${it.timestamp},"source":${json.encodeToString(it.source)},"action":${json.encodeToString(it.action)},"detail":${json.encodeToString(it.detail)}}"""
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"events":[$items]}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleResult() {
        if (!authorized()) return
        val id = call.parameters["id"]
            ?: return error(ErrorCodes.INVALID_REQUEST, "Missing id.")
        val stored = results.get(id)
        if (stored == null) {
            val body = ApiErrorBody(
                envelope(id),
                ErrorDetail(ErrorCodes.RESULT_NOT_FOUND, "Unknown or expired result.")
            )
            call.respondText(
                json.encodeToString(body),
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
            return
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope(id))},"state":"completed","result":${stored.body}}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleOpen() {
        if (!authorized() || rateLimited()) return
        val text = readJsonBody() ?: return
        val url = runCatching {
            json.decodeFromString<OpenRequest>(text).url
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid open request.")
        RequestValidator.checkUrl(url)?.let { return error(it, "Rejected URL.") }
        when (val submitted = arbiter.submit(BrowserCommand.Open(url, "termux"))) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun RoutingContext.handleSearch() {
        if (!authorized() || rateLimited()) return
        val text = readJsonBody() ?: return
        val query = runCatching {
            json.decodeFromString<SearchRequest>(text).query
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid search request.")
        RequestValidator.checkQuery(query)?.let { return error(it, "Rejected query.") }
        val url = UrlPolicy.SEARCH_ENGINE_URL +
            java.net.URLEncoder.encode(query.trim(), "UTF-8")
        when (val submitted = arbiter.submit(BrowserCommand.Open(url, "termux"))) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun RoutingContext.handleNavigate(kind: BrowserCommand.Navigate.Kind) {
        if (!authorized() || rateLimited()) return
        when (val submitted = arbiter.submit(BrowserCommand.Navigate(kind, "termux"))) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun RoutingContext.handleStopCommand() {
        if (!authorized()) return
        arbiter.stopNow("termux")
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"state":"stopped"}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleRead() {
        if (!authorized() || rateLimited()) return
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<ReadRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid read request.")
        RequestValidator.checkRead(request.scope, request.maxChars)?.let {
            return error(it, "Rejected read request.")
        }
        when (val submitted = arbiter.submit(
            BrowserCommand.Read(request.scope, request.maxChars)
        )) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun RoutingContext.handleAiChat() {
        if (!authorized() || rateLimited()) return
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<AiChatRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid ai-chat request.")
        RequestValidator.checkAiChat(request.site, request.prompt, request.maxChars)?.let {
            return error(it, "Rejected ai-chat request.")
        }
        when (val submitted = arbiter.submit(
            BrowserCommand.AiChat(request.site, request.prompt, request.maxChars, "termux")
        )) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun RoutingContext.handleApiChat() {
        if (!authorized() || rateLimited()) return
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<AiChatRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid api-chat request.")
        RequestValidator.checkAiChat(request.site, request.prompt, request.maxChars)?.let {
            return error(it, "Rejected api-chat request.")
        }
        when (val submitted = arbiter.submit(
            BrowserCommand.ApiChat(request.site, request.prompt, request.maxChars, "termux")
        )) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun RoutingContext.handleNotImplemented() {
        if (!authorized()) return
        error(ErrorCodes.INVALID_REQUEST, "ai-chat is reserved for a later milestone.")
    }

    private suspend fun RoutingContext.handlePause() {
        if (!authorized()) return
        policy.onPause()
        log.add("termux", "pause", "")
        bus.publish(EventTypes.AUTOMATION_PAUSED, state = policy.state.name)
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"state":"${policy.state.name}"}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleResume() {
        if (!authorized()) return
        policy.onResume()
        log.add("termux", "resume", "")
        bus.publish(EventTypes.AUTOMATION_RESUMED, state = policy.state.name)
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"state":"${policy.state.name}"}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleControlStop() {
        if (!authorized()) return
        arbiter.stopNow("termux")
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"state":"${policy.state.name}"}""",
            ContentType.Application.Json
        )
    }

    /**
     * Debug mode: JS console messages plus crash records, newest-first
     * replay like /v1/activity. Same bearer auth as every other route.
     */
    private suspend fun RoutingContext.handleDebugConsole() {
        if (!authorized()) return
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val events = debugLog.since(since)
        val items = events.joinToString(",") {
            """{"id":${it.id},"timestamp":${it.timestamp},"source":${json.encodeToString(it.source)},"action":${json.encodeToString(it.action)},"detail":${json.encodeToString(it.detail)}}"""
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"events":[$items]}""",
            ContentType.Application.Json
        )
    }

    /**
     * Debug-only selector probe: counts matches per selector on the live
     * page. Counts only, never text or HTML. Token-gated like everything
     * else; used to fix adapter candidates against real site DOM.
     */
    private suspend fun RoutingContext.handleDebugProbe() {
        if (!authorized() || rateLimited()) return
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<ProbeRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid probe request.")
        if (request.selectors.size > 20 || request.selectors.any { it.length > 200 }) {
            return error(ErrorCodes.INVALID_REQUEST, "Probe bounded to 20 short selectors.")
        }
        val script = "(function(){var S=${json.encodeToString(request.selectors)};" +
            "return JSON.stringify(S.map(function(s){try{return document.querySelectorAll(s).length;}" +
            "catch(e){return -1;}}))})()"
        val raw = arbiter.debugEval(script)
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"counts":${raw ?: "null"}}""",
            ContentType.Application.Json
        )
    }

    /**
     * HTTP mirror of the WebSocket replay: typed bus events newer than
     * since, plus an explicit gap flag. Same data, no socket needed.
     */
    private suspend fun RoutingContext.handleDebugEvents() {
        if (!authorized()) return
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val replay = bus.replaySince(since)
        val items = replay.events.joinToString(",") { json.encodeToString(it) }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"gap":${replay.gap},"events":[$items]}""",
            ContentType.Application.Json
        )
    }

    /** Every retained command result ID with its age: finds lost commands. */
    private suspend fun RoutingContext.handleDebugCommands() {
        if (!authorized()) return
        val now = System.currentTimeMillis()
        val items = results.entries().joinToString(",") {
            """{"commandId":${json.encodeToString(it.commandId)},"ageMs":${now - it.createdAt}}"""
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"results":[$items]}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleDebugReport() {
        if (!authorized()) return
        val report = ui.run { reportProvider() }
        // Manual JSON like /v1/activity: every field always present, so a
        // serializer configuration can never silently drop report fields.
        val lastCrash = report.lastCrash?.let { json.encodeToString(it) } ?: "null"
        val latestCrash = report.latestCrash?.let { json.encodeToString(it) } ?: "null"
        val body = """{"envelope":${json.encodeToString(envelope())},""" +
            """"uptimeMs":${report.uptimeMs},""" +
            """"pendingCommands":${report.pendingCommands},""" +
            """"controlState":${json.encodeToString(report.controlState)},""" +
            """"generationId":${report.generationId},""" +
            """"webViewVersion":${json.encodeToString(report.webViewVersion)},""" +
            """"heapUsedMb":${report.heapUsedMb},""" +
            """"heapMaxMb":${report.heapMaxMb},""" +
            """"lastCrash":$lastCrash,""" +
            """"previousCrashes":${report.previousCrashes},""" +
            """"latestCrash":$latestCrash}"""
        call.respondText(body, ContentType.Application.Json)
    }

    /** Durable crash files from previous runs (full text). */
    private suspend fun RoutingContext.handleDebugCrashes() {
        if (!authorized()) return
        val names = crashRoot?.let { CrashFiles.list(it) } ?: emptyList()
        val items = names.joinToString(",") { json.encodeToString(it) }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"crashes":[$items]}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleDebugCrashFile() {
        if (!authorized()) return
        val root = crashRoot
        val name = call.parameters["name"] ?: ""
        val text = root?.let { CrashFiles.read(it, name) }
        if (text == null) {
            val body = ApiErrorBody(
                envelope(), ErrorDetail(ErrorCodes.NOT_FOUND, "No such crash report.")
            )
            call.respondText(
                json.encodeToString(body),
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
            return
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"name":${json.encodeToString(name)},"text":${json.encodeToString(text)}}""",
            ContentType.Application.Json
        )
    }

    /** Full recording buffer as text: the whole captured log in one call. */
    private suspend fun RoutingContext.handleDebugRecording() {
        if (!authorized()) return
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val entries = Recorder.since(since)
        val items = entries.joinToString(",") {
            """{"id":${it.id},"timestamp":${it.timestamp},"kind":${json.encodeToString(it.kind)},"text":${json.encodeToString(it.text)}}"""
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"recording":${Recorder.enabled},"entries":[$items]}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handleRecordingToggle() {
        if (!authorized()) return
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<RecordingRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid recording request.")
        if (request.enabled) {
            // Fresh capture every start; stopping keeps history for export.
            Recorder.clear()
        }
        Recorder.enabled = request.enabled
        log.add("termux", "recording", if (request.enabled) "started" else "stopped")
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"recording":${Recorder.enabled}}""",
            ContentType.Application.Json
        )
    }

    /**
     * Plugin install: base64 ZIP with exactly the five bundle files at the
     * zip root (anything else, including directories and traversal names,
     * is rejected). Staged, validated, signature-verified, then activated.
     */
    private suspend fun RoutingContext.handlePluginInstall() {
        if (!authorized() || rateLimited()) return
        val store = pluginStore ?: return error(ErrorCodes.INVALID_REQUEST, "Plugins unavailable.")
        val key = pluginRootKey ?: return error(ErrorCodes.INVALID_REQUEST, "No root key.")
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<PluginInstallRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid plugin request.")
        val zipBytes = runCatching {
            java.util.Base64.getDecoder().decode(request.contentBase64)
        }.getOrNull()
        if (zipBytes == null || zipBytes.size > 700 * 1024) {
            return error(ErrorCodes.INVALID_REQUEST, "Bad bundle payload.")
        }
        val staging = store.newStaging()
        try {
            val files = unzipBundle(zipBytes) ?: return error(ErrorCodes.INVALID_REQUEST, "Bad zip.")
            when (val check = PluginBundle.validate(files)) {
                is com.termux.browser.ai.BundleCheck.Rejected ->
                    return error(ErrorCodes.INVALID_REQUEST, "Rejected: ${check.reason}")
                else -> Unit
            }
            val bundle = PluginBundle.assemble(files)
            val manifestBytes = bundle.manifestBytes
            if (!PluginCrypto.verify(manifestBytes, bundle.signature, key)) {
                return error(ErrorCodes.INVALID_REQUEST, "Bad signature.")
            }
            store.canInstall(bundle.manifest.id, bundle.manifest.version)?.let {
                return error(ErrorCodes.INVALID_REQUEST, it)
            }
            // Copy validated files into staging, then activate atomically.
            for ((name, bytes) in files) {
                java.io.File(staging, name).writeBytes(bytes)
            }
            store.activate(bundle.manifest.id, bundle.manifest.version, staging)?.let {
                return error(ErrorCodes.INVALID_REQUEST, it)
            }
            log.add("termux", "plugin_install", "${bundle.manifest.id} v${bundle.manifest.version}")
            onPluginsChanged()
            call.respondText(
                """{"envelope":${json.encodeToString(envelope())},"id":${json.encodeToString(bundle.manifest.id)},"version":${bundle.manifest.version}}""",
                ContentType.Application.Json
            )
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    private fun unzipBundle(zipBytes: ByteArray): Map<String, ByteArray>? {
        return runCatching {
            val files = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Only exact root-level expected files: no directories,
                    // no traversal, no extras, no symlinks-by-construction.
                    if (name !in com.termux.browser.ai.PluginFormat.EXPECTED_FILES) return null
                    if (entry.isDirectory) return null
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 70 * 1024) return null
                        out.write(buffer, 0, read)
                    }
                    files[name] = out.toByteArray()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (files.keys != com.termux.browser.ai.PluginFormat.EXPECTED_FILES) return null
            files.toMap()
        }.getOrNull()
    }

    private suspend fun RoutingContext.handlePluginRollback() {
        if (!authorized()) return
        val store = pluginStore ?: return error(ErrorCodes.INVALID_REQUEST, "Plugins unavailable.")
        val text = readJsonBody() ?: return
        val request = runCatching {
            json.decodeFromString<PluginRollbackRequest>(text)
        }.getOrNull() ?: return error(ErrorCodes.INVALID_REQUEST, "Invalid rollback request.")
        if (!request.id.matches(Regex("[a-z0-9-]{1,32}"))) {
            return error(ErrorCodes.INVALID_REQUEST, "Bad plugin id.")
        }
        store.rollback(request.id)?.let {
            return error(ErrorCodes.INVALID_REQUEST, it)
        }
        log.add("termux", "plugin_rollback", request.id)
        onPluginsChanged()
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"id":${json.encodeToString(request.id)}}""",
            ContentType.Application.Json
        )
    }

    private suspend fun RoutingContext.handlePluginList() {
        if (!authorized()) return
        val items = (pluginStore?.active() ?: emptyList()).joinToString(",") {
            """{"id":${json.encodeToString(it.id)},"version":${it.version}}"""
        }
        call.respondText(
            """{"envelope":${json.encodeToString(envelope())},"plugins":[$items]}""",
            ContentType.Application.Json
        )
    }

    /**
     * Live event stream. Bearer-authenticated like every other route; no
     * unauthenticated path. Replay-then-live with gap semantics comes from
     * EventBus.stream; per-connection slow sends are dropped, never blocking.
     */
    private suspend fun DefaultWebSocketServerSession.handleEvents() {
        val auth = call.request.header("Authorization")
        if (!RequestValidator.checkBearer(auth, token)) {
            // 1008 Policy Violation: standard code, no custom-code interop risk.
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bearer required"))
            return
        }
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        try {
            bus.stream(since) { event ->
                outgoing.trySend(Frame.Text(json.encodeToString(event)))
                Unit
            }
        } catch (_: Exception) {
        }
    }
}
