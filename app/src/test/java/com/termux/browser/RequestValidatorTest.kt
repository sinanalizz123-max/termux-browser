package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestValidatorTest {

    @Test
    fun `oversized bodies are rejected`() {
        assertEquals(
            ErrorCodes.BODY_TOO_LARGE,
            RequestValidator.checkBodySize(ProtocolLimits.MAX_BODY_BYTES + 1)
        )
        assertNull(RequestValidator.checkBodySize(1024))
    }

    @Test
    fun `non-http urls are invalid`() {
        assertEquals(
            ErrorCodes.INVALID_URL,
            RequestValidator.checkUrl("file:///etc/passwd")
        )
        assertNull(RequestValidator.checkUrl("https://example.com"))
    }

    @Test
    fun `blank and oversized queries are invalid`() {
        assertEquals(ErrorCodes.INVALID_REQUEST, RequestValidator.checkQuery("  "))
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkQuery("x".repeat(ProtocolLimits.MAX_QUERY_CHARS + 1))
        )
        assertNull(RequestValidator.checkQuery("hello"))
    }

    @Test
    fun `read scopes and bounds are enforced`() {
        assertEquals(ErrorCodes.INVALID_REQUEST, RequestValidator.checkRead("nope", 100))
        assertEquals(ErrorCodes.INVALID_REQUEST, RequestValidator.checkRead("page", 0))
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkRead("page", ProtocolLimits.MAX_READ_CHARS + 1)
        )
        assertNull(RequestValidator.checkRead("links", 1000))
    }

    @Test
    fun `ai-chat requests are bounded`() {
        assertNull(RequestValidator.checkAiChat("chatgpt", "Summarize this.", 20000))
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkAiChat("", "Hi.", 20000)
        )
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkAiChat("chatgpt", "  ", 20000)
        )
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkAiChat(
                "chatgpt", "x".repeat(ProtocolLimits.MAX_PROMPT_CHARS + 1), 20000
            )
        )
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkAiChat("chatgpt", "Hi.", 0)
        )
        assertEquals(
            ErrorCodes.INVALID_REQUEST,
            RequestValidator.checkAiChat("x".repeat(65), "Hi.", 20000)
        )
    }

    @Test
    fun `bearer check is exact`() {
        val token = ByteArray(32) { it.toByte() }
        val hex = token.joinToString("") { "%02x".format(it) }
        assertTrue(RequestValidator.checkBearer("Bearer $hex", token))
        assertFalse(RequestValidator.checkBearer(null, token))
        assertFalse(RequestValidator.checkBearer("Bearer wrong", token))
        assertFalse(RequestValidator.checkBearer(hex, token))
        assertFalse(RequestValidator.checkBearer("bearer $hex", token))
    }
}
