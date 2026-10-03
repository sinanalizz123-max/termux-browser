package com.termux.browser.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * M10 glue between the arbiter and API providers. Owns no credentials and no
 * WebView: it borrows the key for exactly one request via withKey, and the
 * provider must not retain it. Result payloads use the same JSON shape as
 * web ai-chat results; error codes are the sanitized ApiErrorCodes plus
 * local PROVIDER states. The key never appears in any output.
 */
class AiApiRunner(
    private val credentials: ApiCredentialStore,
    private val providers: Map<String, ApiProvider>,
    private val json: Json = Json
) {
    fun providerIds(): Set<String> = providers.keys

    suspend fun chat(providerId: String, prompt: String, maxChars: Int): String {
        val provider = providers[providerId]
            ?: return errorJson("INVALID_REQUEST")
        if (!credentials.isEnabled(providerId) || !credentials.hasKey(providerId)) {
            return errorJson("PROVIDER_DISABLED")
        }
        val outcome = credentials.withKey(providerId) { key ->
            provider.chat(prompt, key, maxChars)
        } ?: return errorJson("PROVIDER_DISABLED")
        return when (outcome) {
            is ApiOutcome.Text -> {
                val text = outcome.text.take(maxChars)
                """{"state":"completed","text":${json.encodeToString(text)},"truncated":${outcome.text.length > maxChars || outcome.truncated}}"""
            }
            is ApiOutcome.Error -> errorJson(outcome.code)
        }
    }

    private fun errorJson(code: String): String =
        """{"state":"failed","error":"$code"}"""
}
