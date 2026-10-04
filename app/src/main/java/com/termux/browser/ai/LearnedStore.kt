package com.termux.browser.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Learn mode persistence: per-host learned send controls, taught by the
 * user's own taps. Local JSON only. A learned selector upgrades the submit
 * step; detection, health, waiting, and cancellation stay adapter-owned.
 */
@Serializable
data class LearnedControls(
    val sendSelector: String = "",
    val updatedAt: Long = 0L
)

class LearnedStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private fun fileFor(host: String): File {
        val safe = host.lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("").take(64).trim('.', '_')
            .ifEmpty { "unknown" }
        return File(dir, "learned_$safe.json")
    }

    fun get(host: String): LearnedControls? {
        val file = fileFor(host)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<LearnedControls>(file.readText()) }
            .getOrNull()?.takeIf { it.sendSelector.isNotBlank() }
    }

    fun put(host: String, sendSelector: String) {
        require(sendSelector.isNotBlank())
        require(sendSelector.length <= 500)
        dir.mkdirs()
        fileFor(host).writeText(
            json.encodeToString(LearnedControls(sendSelector, clock()))
        )
    }

    fun clear(host: String) {
        runCatching { fileFor(host).delete() }
    }
}

/** Probe script: element under CSS-pixel coordinates as pure structure. */
fun elementProbeScript(xCss: Double, yCss: Double): String {
    require(xCss.isFinite() && yCss.isFinite())
    val x = "%.2f".format(java.util.Locale.US, xCss)
    val y = "%.2f".format(java.util.Locale.US, yCss)
    return "(function(){var e=document.elementFromPoint($x,$y);if(!e)return JSON.stringify({});" +
        "function cls(el){try{return Array.prototype.slice.call(el.classList,0,8);}catch(_){return [];}}" +
        "return JSON.stringify({tag:e.tagName||'',id:e.id||''," +
        "testId:e.getAttribute?((e.getAttribute('data-testid'))||''):''," +
        "ariaLabel:e.getAttribute?((e.getAttribute('aria-label'))||''):''," +
        "role:e.getAttribute?((e.getAttribute('role'))||''):''," +
        "classes:cls(e)})})()"
}

fun parseElementInfo(raw: String?): ElementInfo? {
    if (raw.isNullOrBlank()) return null
    val unquoted = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<String>(raw)
    }.getOrNull() ?: raw
    return runCatching {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }.decodeFromString<ElementInfo>(unquoted)
    }.getOrNull()
}

/** Structural element description from elementFromPoint (no page content). */
@kotlinx.serialization.Serializable
data class ElementInfo(
    val tag: String = "",
    val id: String = "",
    val testId: String = "",
    val ariaLabel: String = "",
    val role: String = "",
    val classes: List<String> = emptyList()
)

object LearnedSelectors {
    private fun cssEscape(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").take(120)

    private fun saneClass(name: String): Boolean =
        name.matches(Regex("[A-Za-z_-][A-Za-z0-9_-]*")) && name.length <= 60

    /**
     * Derives the most stable selector for a tapped element, or null when
     * nothing trustworthy exists. Priority: id, testid, aria-label,
     * role+class, tag+class. Positional selectors are never generated.
     */
    fun derive(info: ElementInfo): String? {
        if (info.id.isNotBlank() && info.id.matches(Regex("[A-Za-z_-][A-Za-z0-9_:.#-]*"))) {
            return "#${info.id.take(120)}"
        }
        if (info.testId.isNotBlank()) {
            return "[data-testid='${cssEscape(info.testId)}']"
        }
        if (info.ariaLabel.isNotBlank()) {
            return "[aria-label='${cssEscape(info.ariaLabel)}']"
        }
        val cls = info.classes.firstOrNull { saneClass(it) }
        val tag = info.tag.lowercase().takeIf { it.matches(Regex("[a-z][a-z0-9]*")) } ?: return null
        if (info.role.isNotBlank() && info.role.matches(Regex("[a-z-]+")) && cls != null) {
            return "$tag[role='${info.role}'].$cls"
        }
        if (cls != null) return "$tag.$cls"
        return null
    }
}
