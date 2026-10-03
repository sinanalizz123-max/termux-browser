package com.termux.browser

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * M2 bearer-token storage. The 256-bit token is encrypted with an
 * Android-Keystore AES-256-GCM key and kept in ordinary private app storage.
 * No EncryptedSharedPreferences, no backups (allowBackup=false), no token in
 * logs/notifications/events/exceptions.
 */
interface TokenCrypto {
    /** Returns (iv, ciphertext). */
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

class KeystoreTokenCrypto : TokenCrypto {
    companion object {
        const val ALIAS = "termux_browser_api"
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv to cipher.doFinal(plain)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}

class TokenStore(
    private val dir: java.io.File,
    private val crypto: TokenCrypto = KeystoreTokenCrypto(),
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        const val TOKEN_BYTES = 32
        private const val FILE_NAME = "api_token.bin"
        private const val IV_BYTES = 12
    }

    @Synchronized
    fun getOrCreate(): ByteArray {
        val file = java.io.File(dir, FILE_NAME)
        if (file.isFile) {
            val raw = runCatching { file.readBytes() }.getOrNull()
            if (raw != null && raw.size > IV_BYTES) {
                val token = runCatching {
                    crypto.decrypt(raw.copyOf(IV_BYTES), raw.copyOfRange(IV_BYTES, raw.size))
                }.getOrNull()
                if (token != null && token.size == TOKEN_BYTES) return token
            }
        }
        return create(file)
    }

    private fun create(file: java.io.File): ByteArray {
        val token = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        val (iv, ciphertext) = crypto.encrypt(token)
        file.writeBytes(iv + ciphertext)
        return token
    }

    fun hex(token: ByteArray): String =
        token.joinToString("") { "%02x".format(it) }
}
