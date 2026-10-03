package com.termux.browser.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {

    private fun client(handler: suspend (String?) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val auth = request.headers[HttpHeaders.Authorization]
                    val (status, body) = handler(auth)
                    respond(body, status, headersOf("Content-Type", "application/json"))
                }
            }
        }
    }

    @Test
    fun `success extracts and truncates`() = runBlocking {
        var authSeen: String? = null
        val provider = OpenAiProvider(
            client { auth ->
                authSeen = auth
                HttpStatusCode.OK to
                    """{"choices":[{"message":{"role":"assistant","content":"${"x".repeat(500)}"}}]}"""
            }
        )
        val outcome = provider.chat("Hi.", "sk-test-KEY", 100) as ApiOutcome.Text
        assertEquals(100, outcome.text.length)
        assertTrue(outcome.truncated)
        assertEquals("Bearer sk-test-KEY", authSeen)
    }

    @Test
    fun `auth rate-limit malformed and transport map distinctly`() = runBlocking {
        suspend fun codeFor(status: HttpStatusCode, body: String): String {
            val provider = OpenAiProvider(client { status to body })
            return (provider.chat("Hi.", "k", 100) as ApiOutcome.Error).code
        }
        assertEquals(ApiErrorCodes.AUTH, codeFor(HttpStatusCode.Unauthorized, "{}"))
        assertEquals(ApiErrorCodes.AUTH, codeFor(HttpStatusCode.Forbidden, "{}"))
        assertEquals(ApiErrorCodes.RATE_LIMITED, codeFor(HttpStatusCode.TooManyRequests, "{}"))
        assertEquals(ApiErrorCodes.TRANSPORT, codeFor(HttpStatusCode.InternalServerError, "{}"))
        assertEquals(ApiErrorCodes.MALFORMED, codeFor(HttpStatusCode.OK, "{}"))
        assertEquals(ApiErrorCodes.MALFORMED, codeFor(HttpStatusCode.OK, "not json"))
    }

    @Test
    fun `timeout is distinct from cancellation`() = runBlocking {
        val slow = OpenAiProvider(
            client {
                delay(5000)
                HttpStatusCode.OK to """{"choices":[]}"""
            },
            timeoutMs = 300
        )
        assertEquals(ApiErrorCodes.TIMEOUT, (slow.chat("Hi.", "k", 100) as ApiOutcome.Error).code)

        val hanging = OpenAiProvider(
            client {
                delay(30000)
                HttpStatusCode.OK to """{"choices":[]}"""
            },
            timeoutMs = 60_000
        )
        val job = launch { hanging.chat("Hi.", "k", 100) }
        delay(200)
        job.cancel()
        var cancelled = false
        try {
            job.join()
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `errors never leak bodies keys or exception text`() = runBlocking {
        val secret = "sk-live-SUPER-SECRET-99"
        val leaky = OpenAiProvider(
            client {
                throw RuntimeException("boom $secret")
            }
        )
        val outcome = leaky.chat("Hi.", secret, 100) as ApiOutcome.Error
        assertFalse(outcome.code.contains(secret))
        val transport = OpenAiProvider(
            client { HttpStatusCode.BadGateway to """{"detail":"$secret leak"}""" }
        )
        val outcome2 = transport.chat("Hi.", secret, 100) as ApiOutcome.Error
        assertFalse(outcome2.code.contains("leak"))
        assertEquals(ApiErrorCodes.TRANSPORT, outcome2.code)
    }
}
