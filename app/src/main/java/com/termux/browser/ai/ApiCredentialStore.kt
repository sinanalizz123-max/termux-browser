package com.termux.browser.ai

import com.termux.browser.TokenCrypto
import java.io.File

/**
 * M10 per-provider API credential lifecycle. Each provider owns an isolated
 * record: its own encrypted file plus its own Keystore alias namespace.
 *
 * - Secrets are never returned through any API: only presence/enabled state.
 * - Disabling blocks requests without deleting other providers' records.
 * - Replacing/deleting overwrites/removes the old ciphertext.
 * - The secret reaches the provider only inside [withKey], for one request;
 *   this store retains no key material in fields.
 */
class ApiCredentialStore(
    private val dir: File,
    private val cryptoFor: (String) -> TokenCrypto
) {
    companion object {
        private val SAFE_ID = Regex("[a-z0-9-]{1,32}")
        private const val IV_BYTES = 12
    }

    private fun fileFor(providerId: String): File {
        require(SAFE_ID.matches(providerId)) { "bad provider id" }
        return File(dir, "apikey_$providerId.bin")
    }

    private fun metaFileFor(providerId: String): File {
        require(SAFE_ID.matches(providerId)) { "bad provider id" }
        return File(dir, "apikey_$providerId.meta")
    }

    fun setKey(providerId: String, key: String) {
        require(key.isNotBlank()) { "empty key" }
        val crypto = cryptoFor(providerId)
        val (iv, ciphertext) = crypto.encrypt(key.toByteArray(Charsets.UTF_8))
        val file = fileFor(providerId)
        file.writeBytes(iv + ciphertext)
        metaFileFor(providerId).writeText("enabled=true")
    }

    fun deleteKey(providerId: String) {
        runCatching { fileFor(providerId).delete() }
        runCatching { metaFileFor(providerId).delete() }
    }

    fun setEnabled(providerId: String, enabled: Boolean) {
        if (!fileFor(providerId).isFile) return
        metaFileFor(providerId).writeText("enabled=$enabled")
    }

    fun isEnabled(providerId: String): Boolean {
        if (!fileFor(providerId).isFile) return false
        return runCatching {
            metaFileFor(providerId).readText().trim() == "enabled=true"
        }.getOrDefault(false)
    }

    fun hasKey(providerId: String): Boolean = fileFor(providerId).isFile

    /**
     * Runs [block] with the decrypted key, or null when disabled/missing.
     * Nothing is retained afterwards.
     */
    suspend fun <T> withKey(providerId: String, block: suspend (String) -> T): T? {
        if (!isEnabled(providerId)) return null
        val raw = runCatching { fileFor(providerId).readBytes() }.getOrNull()
            ?: return null
        if (raw.size <= IV_BYTES) return null
        val plain = runCatching {
            cryptoFor(providerId).decrypt(
                raw.copyOf(IV_BYTES),
                raw.copyOfRange(IV_BYTES, raw.size)
            )
        }.getOrNull() ?: return null
        return block(plain.toString(Charsets.UTF_8))
    }
}
