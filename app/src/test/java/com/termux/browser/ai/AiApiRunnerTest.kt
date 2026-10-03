package com.termux.browser.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiApiRunnerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fakeCrypto = object : com.termux.browser.TokenCrypto {
        override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> =
            ByteArray(12) { 7 } to plain.copyOf()

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            ciphertext.copyOf()
    }

    private val seenAuth = mutableListOf<String?>()

    private fun runner(store: ApiCredentialStore): AiApiRunner {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    seenAuth.add(request.headers[HttpHeaders.Authorization])
                    respond(
                        """{"choices":[{"message":{"role":"assistant","content":"mock answer"}}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json")
                    )
                }
            }
        }
        return AiApiRunner(store, mapOf("openai" to OpenAiProvider(client)))
    }

    @Test
    fun `replace disable re-enable lifecycle with no key leakage`() = runBlocking {
        seenAuth.clear()
        val key = "sk-live-KEY-ONE"
        val store = ApiCredentialStore(folder.newFolder()) { fakeCrypto }
        val api = runner(store)

        // Unknown provider and missing key fail closed.
        assertTrue(api.chat("nope", "Hi.", 100).contains("INVALID_REQUEST"))
        assertTrue(api.chat("openai", "Hi.", 100).contains("PROVIDER_DISABLED"))

        // Set key: works, payload carries the answer, never the key.
        store.setKey("openai", key)
        val first = api.chat("openai", "Hi.", 100)
        assertTrue(first.contains("mock answer"))
        assertFalse(first.contains(key))

        // Disable: blocked, key untouched.
        store.setEnabled("openai", false)
        assertTrue(api.chat("openai", "Hi.", 100).contains("PROVIDER_DISABLED"))

        // Replace + re-enable: the new key is used, never echoed.
        val replacement = "sk-live-KEY-TWO"
        store.setKey("openai", replacement)
        store.setEnabled("openai", true)
        val second = api.chat("openai", "Hi.", 100)
        assertTrue(second.contains("mock answer"))
        assertFalse(second.contains(replacement))
        // Each request carried exactly the current key at request time.
        assertTrue(seenAuth == listOf("Bearer $key", "Bearer $replacement"))
    }

    @Test
    fun `empty provider map fails closed`() = runBlocking {
        val store = ApiCredentialStore(folder.newFolder()) { fakeCrypto }
        val api = AiApiRunner(store, emptyMap())
        assertTrue(api.chat("openai", "Hi.", 100).contains("INVALID_REQUEST"))
    }
}
