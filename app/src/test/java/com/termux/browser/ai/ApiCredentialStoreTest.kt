package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiCredentialStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fakeCrypto = object : com.termux.browser.TokenCrypto {
        override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> =
            ByteArray(12) { 7 } to plain.copyOf()

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            ciphertext.copyOf()
    }

    private fun store(): ApiCredentialStore =
        ApiCredentialStore(folder.newFolder()) { fakeCrypto }

    @Test
    fun `missing key disables requests`() {
        val store = store()
        assertFalse(store.hasKey("openai"))
        assertFalse(store.isEnabled("openai"))
        var called = false
        kotlinx.coroutines.runBlocking {
            assertNull(store.withKey("openai") { called = true })
        }
        assertFalse(called)
    }

    @Test
    fun `setKey enables and round-trips only inside withKey`() {
        val store = store()
        store.setKey("openai", "sk-test-123")
        assertTrue(store.hasKey("openai"))
        assertTrue(store.isEnabled("openai"))
        var seen: String? = null
        kotlinx.coroutines.runBlocking {
            store.withKey("openai") { seen = it }
        }
        assertEquals("sk-test-123", seen)
    }

    @Test
    fun `disable blocks without deleting`() {
        val store = store()
        store.setKey("openai", "sk-test-123")
        store.setEnabled("openai", false)
        assertFalse(store.isEnabled("openai"))
        assertTrue(store.hasKey("openai"))
        var called = false
        kotlinx.coroutines.runBlocking {
            assertNull(store.withKey("openai") { called = true })
        }
        assertFalse(called)
        store.setEnabled("openai", true)
        var seen: String? = null
        kotlinx.coroutines.runBlocking {
            store.withKey("openai") { seen = it }
        }
        assertEquals("sk-test-123", seen)
    }

    @Test
    fun `replace overwrites and delete removes`() {
        val store = store()
        store.setKey("openai", "sk-old")
        store.setKey("openai", "sk-new")
        var seen: String? = null
        kotlinx.coroutines.runBlocking {
            store.withKey("openai") { seen = it }
        }
        assertEquals("sk-new", seen)
        store.deleteKey("openai")
        assertFalse(store.hasKey("openai"))
        assertFalse(store.isEnabled("openai"))
    }

    @Test
    fun `providers are isolated`() {
        val store = store()
        store.setKey("openai", "sk-openai")
        assertFalse(store.hasKey("other"))
        assertFalse(store.isEnabled("other"))
    }

    @Test
    fun `bad ids are rejected`() {
        val store = store()
        for (bad in listOf("", "../x", "A B", "x".repeat(33))) {
            var threw = false
            try {
                store.setKey(bad, "k")
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("must reject $bad", threw)
        }
    }
}
