package com.termux.browser

import java.security.MessageDigest

/**
 * Pure HTTP/request validation for the control plane. No Android APIs.
 */
object RequestValidator {

    fun checkBodySize(bytes: Int): String? =
        if (bytes > ProtocolLimits.MAX_BODY_BYTES) ErrorCodes.BODY_TOO_LARGE else null

    fun checkUrl(url: String): String? {
        if (url.length > ProtocolLimits.MAX_URL_CHARS) return ErrorCodes.INVALID_REQUEST
        if (!UrlPolicy.isAllowed(url)) return ErrorCodes.INVALID_URL
        return null
    }

    fun checkQuery(query: String): String? {
        if (query.isBlank()) return ErrorCodes.INVALID_REQUEST
        if (query.length > ProtocolLimits.MAX_QUERY_CHARS) return ErrorCodes.INVALID_REQUEST
        return null
    }

    fun checkAiChat(site: String, prompt: String, maxChars: Int): String? {
        if (site.isBlank() || site.length > 64) return ErrorCodes.INVALID_REQUEST
        if (prompt.isBlank() || prompt.length > ProtocolLimits.MAX_PROMPT_CHARS) {
            return ErrorCodes.INVALID_REQUEST
        }
        if (maxChars <= 0 || maxChars > ProtocolLimits.MAX_READ_CHARS) {
            return ErrorCodes.INVALID_REQUEST
        }
        return null
    }

    fun checkRead(scope: String, maxChars: Int): String? {
        if (scope !in ProtocolLimits.READ_SCOPES) return ErrorCodes.INVALID_REQUEST
        if (maxChars <= 0 || maxChars > ProtocolLimits.MAX_READ_CHARS) {
            return ErrorCodes.INVALID_REQUEST
        }
        return null
    }

    /** Constant-time bearer check. Header must be exactly "Bearer <hex>". */
    fun checkBearer(header: String?, expectedToken: ByteArray): Boolean {
        if (header == null) return false
        val expected = ("Bearer " + expectedToken.joinToString("") { "%02x".format(it) })
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(header.toByteArray(Charsets.UTF_8), expected)
    }
}
