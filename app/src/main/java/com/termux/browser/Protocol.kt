package com.termux.browser

import kotlinx.serialization.Serializable

/**
 * M0/M2 protocol models. Wire format is JSON; data reaches JavaScript only
 * through JSON serialization, never string concatenation.
 */
@Serializable
data class Envelope(
    val protocolVersion: String = "1",
    val requestId: String,
    val commandId: String? = null,
    val generationId: Int? = null
)

@Serializable
data class ApiErrorBody(
    val envelope: Envelope,
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    val recoverable: Boolean = false
)

object ErrorCodes {
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_URL = "INVALID_URL"
    const val BODY_TOO_LARGE = "BODY_TOO_LARGE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val QUEUE_FULL = "QUEUE_FULL"
    const val SERVER_NOT_READY = "SERVER_NOT_READY"
    const val NOT_FOUND = "NOT_FOUND"
    const val RESULT_NOT_FOUND = "RESULT_NOT_FOUND"
    const val BUSY = "BUSY"
    const val CANCELLED = "CANCELLED"
    const val LOGIN_REQUIRED = "LOGIN_REQUIRED"
    const val CAPTCHA_DETECTED = "CAPTCHA_DETECTED"
    const val USER_TAKEOVER = "USER_TAKEOVER"
    const val NAVIGATION_CHANGED = "NAVIGATION_CHANGED"
    const val TIMEOUT = "TIMEOUT"
    const val DOM_CHANGED = "DOM_CHANGED"
    const val AI_ERROR = "AI_ERROR"
}

@Serializable
data class StatusBody(
    val envelope: Envelope,
    val state: String,
    val url: String? = null,
    val title: String? = null,
    val loading: Boolean = false,
    val generationId: Int = 0,
    val webViewVersion: String = "unknown"
)

@Serializable
data class AcceptedBody(
    val envelope: Envelope,
    val accepted: Boolean = true,
    val state: String = "queued"
)

@Serializable
data class OpenRequest(val url: String = "")

@Serializable
data class SearchRequest(val query: String = "")

@Serializable
data class ReadRequest(
    val scope: String = "page",
    val maxChars: Int = 20000
)

@Serializable
data class ReadResult(
    val scope: String,
    val text: String? = null,
    val title: String? = null,
    val links: List<LinkResult> = emptyList(),
    val truncated: Boolean = false
)

@Serializable
data class LinkResult(
    val text: String,
    val href: String,
    val visible: Boolean = true
)

object ProtocolLimits {
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_URL_CHARS = 4096
    const val MAX_QUERY_CHARS = 1000
    const val MAX_PROMPT_CHARS = 20000
    const val MAX_READ_CHARS = 50000
    const val MAX_QUEUE = 16
    const val MAX_RESULTS = 32
    const val MAX_RESULT_BYTES = 256 * 1024
    const val RESULT_TTL_MS = 5 * 60 * 1000L

    val READ_SCOPES = setOf("page", "title", "links", "visible_text", "selection")
}
