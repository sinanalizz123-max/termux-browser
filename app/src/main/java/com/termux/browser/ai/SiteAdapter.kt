package com.termux.browser.ai

/**
 * M7 provider-agnostic AI adapter contract.
 *
 * Hard rule: detect(url) is side-effect-free. It operates solely on the URL,
 * host, and immutable adapter metadata — no WebView inspection, no JS
 * execution, no network, no state mutation. Detection must stay a cheap,
 * deterministic pure function.
 */
data class SelectorSet(
    val prompt: List<String> = emptyList(),
    val submit: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val stop: List<String> = emptyList()
)

data class AdapterHealth(
    val site: String,
    val recognized: Boolean,
    val promptInput: Boolean = false,
    val submitControl: Boolean = false,
    val responseContainer: Boolean = false,
    val loginState: String = "unknown",
    val adapterVersion: String = "1",
    val host: String = "",
    val uiVariant: String = ""
)

interface SiteAdapter {
    val id: String

    /** Exact hosts this adapter owns. Empty = generic fallback. */
    val hosts: Set<String>

    fun detect(url: String): Boolean
    fun selectors(): SelectorSet

    /** Static, zero-parameter snapshot script returning Snapshot JSON. */
    fun snapshotScript(): String
}

/**
 * Registry with deterministic precedence: exact host owners first (in
 * registration order), generic fallback last. A generic adapter can never
 * shadow a specific one.
 */
class AdapterRegistry(adapters: List<SiteAdapter>) {

    private val ordered = adapters.toList()

    fun detect(url: String): SiteAdapter? {
        val host = hostOf(url)
        ordered.firstOrNull { adapter ->
            adapter.hosts.isNotEmpty() &&
                (host in adapter.hosts || adapter.detect(url))
        }?.let { return it }
        return ordered.firstOrNull { it.hosts.isEmpty() && it.detect(url) }
    }

    fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        return withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    }
}

/**
 * Generic fallback adapter. Only generic, semantic selectors — no
 * provider-specific knowledge. Specific adapters (later milestones) own
 * their hosts and take precedence in the registry.
 */
class GenericAdapter : SiteAdapter {
    override val id = "generic"
    override val hosts: Set<String> = emptySet()

    override fun detect(url: String): Boolean = true

    override fun selectors(): SelectorSet = SelectorSet(
        prompt = listOf(
            "textarea",
            "textarea[name='prompt']",
            "[contenteditable='true']",
            "input[type='text']"
        ),
        submit = listOf(
            "button[type='submit']",
            "[data-composer-submit]",
            "button[aria-label*='Send']"
        ),
        messages = listOf(
            "[data-message-role='assistant']",
            "[role='log']",
            "main",
            "article"
        ),
        stop = listOf("[aria-label*='Stop']")
    )

    override fun snapshotScript(): String =
        "(function(){var m=document.querySelectorAll('[data-message-role],article,[role=\"log\"]');" +
            "var last=m.length?m[m.length-1].innerText:'';" +
            "var stop=!!document.querySelector('[aria-label*=\"Stop\"]');" +
            "return JSON.stringify({messageCount:m.length,lastText:(last||'').slice(-4000),generating:stop})})()"
}
