package com.termux.browser.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Page context: the first path segment of a URL, so teachings from one
 * route (e.g. DeepSeek "/") are never trusted on another (e.g. DeepSeek
 * "/a/chat/s/<id>"). Empty for the site home page.
 */
fun pageContext(url: String): String {
    val path = url.substringAfter("://", url).substringAfter('/', "")
        .substringBefore('?').substringBefore('#')
    val first = path.split('/').firstOrNull { it.isNotEmpty() } ?: ""
    return first.take(48).lowercase()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("").trim('_')
}

/**
 * Learn mode persistence: learned controls taught by the user's own taps,
 * scoped by host AND page context. Local JSON only. A learned selector
 * upgrades the submit step; detection, health, waiting, and cancellation
 * stay adapter-owned.
 */
@Serializable
data class LearnedControl(
    val selector: String = "",
    val updatedAt: Long = 0L,
    val context: String = "",
    val stale: Boolean = false
)

@Serializable
data class LearnedControls(
    val sendSelector: String = "",
    val updatedAt: Long = 0L,
    val controls: Map<String, LearnedControl> = emptyMap()
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
            .getOrNull()?.takeIf { it.sendSelector.isNotBlank() || it.controls.isNotEmpty() }
    }

    /**
     * Named control (e.g. "send", "menu") for an exact page context.
     * Cross-context use is callers' responsibility: the submit path must
     * only request the current page's context, so a teaching from "/"
     * can never drive a tap on "/a/chat/s/<id>".
     */
    fun getControl(host: String, control: String, context: String = ""): String? {
        val controls = get(host) ?: return null
        if (context.isEmpty()) {
            // Legacy host-level lookup (explicit tap-control path only).
            if (control == "send" && controls.sendSelector.isNotBlank()) {
                return controls.sendSelector
            }
            return controls.controls[control]?.selector?.takeIf { it.isNotBlank() && !it.stale }
        }
        val key = scopedKey(context, control)
        // Scoped map first, then the legacy host-level send selector only
        // when it was explicitly taught for this same context is impossible
        // to know — so legacy entries never serve scoped lookups.
        return controls.controls[key]?.takeIf { it.selector.isNotBlank() && !it.stale }?.selector
    }

    fun put(host: String, sendSelector: String) {
        putControl(host, "send", sendSelector)
    }

    fun putControl(host: String, control: String, selector: String, context: String = "") {
        require(control.matches(Regex("[a-z]{1,16}")))
        require(selector.isNotBlank())
        require(selector.length <= 500)
        require(context.length <= 48)
        dir.mkdirs()
        val current = get(host)
        val key = if (context.isEmpty()) control else scopedKey(context, control)
        val entry = LearnedControl(selector, clock(), context)
        val merged = (current?.controls ?: emptyMap()) + (key to entry)
        val send = if (control == "send" && context.isEmpty()) selector else (current?.sendSelector ?: "")
        fileFor(host).writeText(
            json.encodeToString(LearnedControls(send, clock(), merged))
        )
    }

    /**
     * Marks a teaching stale after it fails semantic validation, so the
     * next run falls back to the adapter selector instead of tapping a
     * control that is no longer the send button.
     */
    fun markStale(host: String, control: String, context: String = "") {
        val current = get(host) ?: return
        val key = if (context.isEmpty()) control else scopedKey(context, control)
        val entry = current.controls[key] ?: return
        val merged = current.controls + (key to entry.copy(stale = true, updatedAt = clock()))
        fileFor(host).writeText(
            json.encodeToString(LearnedControls(current.sendSelector, clock(), merged))
        )
    }

    private fun scopedKey(context: String, control: String) = "$context\u0001$control"

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
