package com.termux.browser.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M10 API-backed AI. Deliberately separate from SiteAdapter: no WebView, no
 * DOM, no browser assumptions. The external contract returns only the final
 * bounded response; providers may consume streaming internally.
 *
 * Error taxonomy (stable, sanitized — never raw bodies/headers/keys):
 * AUTH, RATE_LIMITED, TIMEOUT, CANCELLED, MALFORMED, TRANSPORT.
 */
sealed interface ApiOutcome {
    data class Text(val text: String, val truncated: Boolean) : ApiOutcome
    data class Error(val code: String) : ApiOutcome
}

object ApiErrorCodes {
    const val AUTH = "API_AUTH"
    const val RATE_LIMITED = "API_RATE_LIMITED"
    const val TIMEOUT = "API_TIMEOUT"
    const val CANCELLED = "API_CANCELLED"
    const val MALFORMED = "API_MALFORMED"
    const val TRANSPORT = "API_TRANSPORT"
}

interface ApiProvider {
    val id: String

    /**
     * One request with a caller-supplied credential. Implementations must
     * not retain the credential beyond the call.
     */
    suspend fun chat(prompt: String, credential: String, maxChars: Int): ApiOutcome
}

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

class OpenAiProvider(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini",
    private val timeoutMs: Long = 120_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ApiProvider {
    override val id = "openai"

    override suspend fun chat(prompt: String, credential: String, maxChars: Int): ApiOutcome {
        val bound = maxChars.coerceIn(1, 50_000)
        return try {
            withTimeout(timeoutMs) {
                // Explicit JSON string: no reliance on client plugins.
                val body = json.encodeToString(
                    ChatRequest(
                        model = model,
                        messages = listOf(ChatMessage("user", prompt))
                    )
                )
                val response = client.post("$baseUrl/chat/completions") {
                    header("Authorization", "Bearer $credential")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                when (response.status) {
                    HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                        ApiOutcome.Error(ApiErrorCodes.AUTH)
                    HttpStatusCode.TooManyRequests ->
                        ApiOutcome.Error(ApiErrorCodes.RATE_LIMITED)
                    else -> {
                        if (response.status.value !in 200..299) {
                            return@withTimeout ApiOutcome.Error(ApiErrorCodes.TRANSPORT)
                        }
                        val parsed = runCatching {
                            json.decodeFromString<ChatResponse>(response.bodyAsText())
                        }.getOrNull()
                        val text = parsed?.choices?.firstOrNull()?.message?.content
                        if (text.isNullOrBlank()) {
                            ApiOutcome.Error(ApiErrorCodes.MALFORMED)
                        } else {
                            ApiOutcome.Text(
                                text = text.take(bound),
                                truncated = text.length > bound
                            )
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ApiOutcome.Error(ApiErrorCodes.TIMEOUT)
        } catch (e: CancellationException) {
            // External scope cancellation must propagate so the arbiter
            // worker dies instead of recording a result after close().
            throw e
        } catch (_: Exception) {
            ApiOutcome.Error(ApiErrorCodes.TRANSPORT)
        }
    }
}
