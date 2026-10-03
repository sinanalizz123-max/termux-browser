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
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
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
                    webSocket("/events") { handleEvents() }
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
