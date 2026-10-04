package com.termux.browser.ai

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Bundle signature verification. Platform RSA/SHA-256 only (universally
 * available on minSdk 26 — no bundled crypto, no algorithm fallback).
 * The signature covers the exact manifest bytes; file integrity comes from
 * the manifest's per-file hashes.
 */
object PluginCrypto {
    fun publicKeyFromDer(der: ByteArray): PublicKey? {
        return runCatching {
            KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(der))
        }.getOrNull()
    }

    fun verify(manifestBytes: ByteArray, signature: ByteArray, key: PublicKey): Boolean {
        if (signature.size != 256) return false
        return runCatching {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(manifestBytes)
            verifier.verify(signature)
        }.getOrDefault(false)
    }
}
