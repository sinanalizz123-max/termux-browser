package com.termux.browser.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Declarative plugin bundle (M-plguin backend). JSON ONLY — there is no
 * place in this format for code, scripts, expressions, or URLs to execute.
 * The APK engine interprets selector lists and a fixed submit-strategy
 * vocabulary; unknown fields are rejected, never ignored.
 */
object PluginFormat {
    const val FORMAT = "tb-plugin/1"
    const val CORE_VERSION = 1
    const val KEY_ID = "termux-1"

    const val MAX_BUNDLE_BYTES = 512 * 1024
    const val MAX_FILES = 6
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_SELECTORS = 30
    const val MAX_SELECTOR_CHARS = 200
    const val MAX_STRING_CHARS = 2000
    const val MAX_HOSTS = 5

    val EXPECTED_FILES = setOf(
        "manifest.json", "selectors.json", "health.json", "plan.json", "signature.sig"
    )
}

@Serializable
data class PluginManifest(
    val format: String = "",
    val id: String = "",
    val version: Int = 0,
    val minCore: Int = 0,
    val keyId: String = "",
    val hosts: List<String> = emptyList(),
    val files: Map<String, String> = emptyMap()
)

@Serializable
data class PluginSelectors(
    val prompt: List<String> = emptyList(),
    val submit: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val stop: List<String> = emptyList()
)

@Serializable
data class PluginHealth(
    val marker: String? = null,
    val strategies: List<String> = listOf("click", "enter")
)

data class ValidBundle(
    val manifest: PluginManifest,
    val selectors: PluginSelectors,
    val health: PluginHealth,
    val manifestBytes: ByteArray,
    val signature: ByteArray
)

sealed interface BundleCheck {
    data object Ok : BundleCheck
    data class Rejected(val reason: String) : BundleCheck
}

object PluginBundle {
    private val json = Json { ignoreUnknownKeys = false }

    private val HOST = Regex("[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?")
    private val HEX64 = Regex("[0-9a-f]{64}")

    /**
     * Validates extracted bundle files (name -> bytes). The signature itself
     * is verified separately against manifestBytes by PluginCrypto.
     */
    fun validate(files: Map<String, ByteArray>): BundleCheck {
        if (files.keys != PluginFormat.EXPECTED_FILES) {
            return BundleCheck.Rejected("unexpected file set")
        }
        for ((name, bytes) in files) {
            if (bytes.size > PluginFormat.MAX_FILE_BYTES) {
                return BundleCheck.Rejected("oversize file: $name")
            }
        }
        val manifestBytes = files["manifest.json"] ?: return BundleCheck.Rejected("no manifest")
        val signature = files["signature.sig"] ?: return BundleCheck.Rejected("no signature")
        if (signature.isEmpty() || signature.size > 1024) {
            return BundleCheck.Rejected("bad signature length")
        }
        val manifest = runCatching {
            json.decodeFromString<PluginManifest>(manifestBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return BundleCheck.Rejected("malformed manifest")
        // Strict schema: unknown fields already rejected by the parser above.
        if (manifest.format != PluginFormat.FORMAT) return BundleCheck.Rejected("bad format")
        if (manifest.id.isBlank() || manifest.id.length > 64) return BundleCheck.Rejected("bad id")
        if (manifest.version < 1) return BundleCheck.Rejected("bad version")
        if (manifest.minCore < 1 || manifest.minCore > PluginFormat.CORE_VERSION) {
            return BundleCheck.Rejected("incompatible core")
        }
        if (manifest.keyId != PluginFormat.KEY_ID) return BundleCheck.Rejected("unknown key")
        if (manifest.hosts.isEmpty() || manifest.hosts.size > PluginFormat.MAX_HOSTS) {
            return BundleCheck.Rejected("bad hosts")
        }
        if (manifest.hosts.any { !HOST.matches(it) }) return BundleCheck.Rejected("bad host")
        if (manifest.files.keys != setOf("selectors.json", "health.json", "plan.json")) {
            return BundleCheck.Rejected("bad file manifest")
        }
        for ((name, hex) in manifest.files) {
            if (!HEX64.matches(hex)) return BundleCheck.Rejected("bad hash for $name")
            val actual = files[name] ?: return BundleCheck.Rejected("missing $name")
            if (sha256Hex(actual) != hex) return BundleCheck.Rejected("hash mismatch: $name")
        }
        val selectors = runCatching {
            json.decodeFromString<PluginSelectors>(
                files["selectors.json"]!!.toString(Charsets.UTF_8)
            )
        }.getOrNull() ?: return BundleCheck.Rejected("malformed selectors")
        val allSelectors = selectors.prompt + selectors.submit + selectors.messages + selectors.stop
        if (allSelectors.isEmpty() || allSelectors.size > PluginFormat.MAX_SELECTORS) {
            return BundleCheck.Rejected("bad selector count")
        }
        if (allSelectors.any { it.isBlank() || it.length > PluginFormat.MAX_SELECTOR_CHARS }) {
            return BundleCheck.Rejected("bad selector")
        }
        if (selectors.prompt.isEmpty() || selectors.submit.isEmpty()) {
            return BundleCheck.Rejected("prompt and submit required")
        }
        val health = runCatching {
            json.decodeFromString<PluginHealth>(
                files["health.json"]!!.toString(Charsets.UTF_8)
            )
        }.getOrNull() ?: return BundleCheck.Rejected("malformed health")
        if ((health.marker?.length ?: 0) > PluginFormat.MAX_SELECTOR_CHARS) {
            return BundleCheck.Rejected("bad marker")
        }
        if (health.strategies.isEmpty() || health.strategies.any { it != "click" && it != "enter" }) {
            return BundleCheck.Rejected("bad strategies")
        }
        // plan.json is reserved for future strategy parameters; must parse
        // as a JSON object today so malformed content fails closed.
        val planRaw = files["plan.json"]!!.toString(Charsets.UTF_8)
        val planObj = runCatching { json.parseToJsonElement(planRaw).jsonObject }.getOrNull()
            ?: return BundleCheck.Rejected("malformed plan")
        if (planObj.keys.any { it !in setOf("strategies", "requireButton") }) {
            return BundleCheck.Rejected("unknown plan field")
        }
        return BundleCheck.Ok
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Re-parses validated files into a bundle (call only after validate). */
    fun assemble(files: Map<String, ByteArray>): ValidBundle {
        val manifestBytes = files["manifest.json"]!!
        val manifest = Json.decodeFromString<PluginManifest>(manifestBytes.toString(Charsets.UTF_8))
        val selectors = Json.decodeFromString<PluginSelectors>(
            files["selectors.json"]!!.toString(Charsets.UTF_8)
        )
        val health = Json.decodeFromString<PluginHealth>(
            files["health.json"]!!.toString(Charsets.UTF_8)
        )
        return ValidBundle(manifest, selectors, health, manifestBytes, files["signature.sig"]!!)
    }
}
