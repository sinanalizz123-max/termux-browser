package com.termux.browser

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
    private val status: suspend () -> StatusBody,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
) {
    private var engine: ApplicationEngine? = null
    var port: Int = -1
        private set

    private val commandLimiter = RateLimiter(max = 60, windowMs = 60_000)

    fun start(): Int {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) { json(json) }
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
                    post("/commands/ai-chat") { handleNotImplemented() }
                    post("/control/pause") { handlePause() }
                    post("/control/resume") { handleResume() }
                    post("/control/stop") { handleControlStop() }
                }
            }
        }.start(wait = false)
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

    private fun requestId(call: ApplicationCall): String =
        call.request.header("X-Request-Id")?.take(64) ?: ("req-" + UUID.randomUUID())

    private fun envelope(
        call: ApplicationCall,
        commandId: String? = null
    ): Envelope = Envelope(
        requestId = requestId(call),
        commandId = commandId,
        generationId = policy.generation
    )

    private suspend fun ApplicationCall.authorized(): Boolean {
        if (!RequestValidator.checkBearer(request.header("Authorization"), token)) {
            val body = ApiErrorBody(
                envelope = envelope(this),
                error = ErrorDetail(ErrorCodes.AUTH_REQUIRED, "Missing or invalid bearer token.")
            )
            respondText(
                json.encodeToString(body),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
            return false
        }
        return true
    }

    private suspend fun ApplicationCall.readJsonBody(): String? {
        if (request.header("Content-Type")?.startsWith("application/json") != true) {
            error(ErrorCodes.INVALID_REQUEST, "Content-Type must be application/json.")
            return null
        }
        val text = runCatching { receiveText() }.getOrNull()
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

    private suspend fun ApplicationCall.error(code: String, message: String) {
        val body = ApiErrorBody(envelope(this), ErrorDetail(code, message))
        respondText(
            json.encodeToString(body),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
    }

    private suspend fun ApplicationCall.rateLimited(): Boolean {
        if (commandLimiter.tryAcquire()) return false
        val body = ApiErrorBody(
            envelope(this),
            ErrorDetail(ErrorCodes.RATE_LIMITED, "Too many requests.", recoverable = true)
        )
        respondText(
            json.encodeToString(body),
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests
        )
        return true
    }

    private suspend fun ApplicationCall.accepted(accepted: SubmitResult.Accepted) {
        val body = AcceptedBody(envelope(this, accepted.commandId))
        respondText(json.encodeToString(body), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.queueFull() {
        val body = ApiErrorBody(
            envelope(this),
            ErrorDetail(ErrorCodes.QUEUE_FULL, "Command queue is full.", recoverable = true)
        )
        respondText(
            json.encodeToString(body),
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests
        )
    }

    private suspend fun ApplicationCall.handleStatus() {
        if (!authorized()) return
        val current = ui.run { status() }
        val body = current.copy(envelope = envelope(this))
        respondText(json.encodeToString(body), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.handleActivity() {
        if (!authorized()) return
        val since = request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val events = log.since(since)
        val items = events.joinToString(",") {
            """{"id":${it.id},"timestamp":${it.timestamp},"source":${json.encodeToString(it.source)},"action":${json.encodeToString(it.action)},"detail":${json.encodeToString(it.detail)}}"""
        }
        respondText(
            """{"envelope":${json.encodeToString(envelope(this))},"events":[$items]}""",
            ContentType.Application.Json
        )
    }

    private suspend fun ApplicationCall.handleResult() {
        if (!authorized()) return
        val id = parameters["id"] ?: return error(ErrorCodes.INVALID_REQUEST, "Missing id.")
        val stored = results.get(id)
        if (stored == null) {
            val body = ApiErrorBody(
                envelope(this, id),
                ErrorDetail(ErrorCodes.RESULT_NOT_FOUND, "Unknown or expired result.")
            )
            respondText(
                json.encodeToString(body),
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
            return
        }
        respondText(
            """{"envelope":${json.encodeToString(envelope(this, id))},"state":"completed","result":${stored.body}}""",
            ContentType.Application.Json
        )
    }

    private suspend fun ApplicationCall.handleOpen() {
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

    private suspend fun ApplicationCall.handleSearch() {
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

    private suspend fun ApplicationCall.handleNavigate(kind: BrowserCommand.Navigate.Kind) {
        if (!authorized() || rateLimited()) return
        when (val submitted = arbiter.submit(BrowserCommand.Navigate(kind, "termux"))) {
            is SubmitResult.Accepted -> accepted(submitted)
            is SubmitResult.QueueFull -> queueFull()
        }
    }

    private suspend fun ApplicationCall.handleStopCommand() {
        if (!authorized()) return
        arbiter.stopNow("termux")
        respondText(
            """{"envelope":${json.encodeToString(envelope(this))},"state":"stopped"}""",
            ContentType.Application.Json
        )
    }

    private suspend fun ApplicationCall.handleRead() {
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

    private suspend fun ApplicationCall.handleNotImplemented() {
        if (!authorized()) return
        error(ErrorCodes.INVALID_REQUEST, "ai-chat is reserved for a later milestone.")
    }

    private suspend fun ApplicationCall.handlePause() {
        if (!authorized()) return
        policy.onPause()
        log.add("termux", "pause", "")
        respondText(
            """{"envelope":${json.encodeToString(envelope(this))},"state":"${policy.state.name}"}""",
            ContentType.Application.Json
        )
    }

    private suspend fun ApplicationCall.handleResume() {
        if (!authorized()) return
        policy.onResume()
        log.add("termux", "resume", "")
        respondText(
            """{"envelope":${json.encodeToString(envelope(this))},"state":"${policy.state.name}"}""",
            ContentType.Application.Json
        )
    }

    private suspend fun ApplicationCall.handleControlStop() {
        if (!authorized()) return
        arbiter.stopNow("termux")
        respondText(
            """{"envelope":${json.encodeToString(envelope(this))},"state":"${policy.state.name}"}""",
            ContentType.Application.Json
        )
    }
}
