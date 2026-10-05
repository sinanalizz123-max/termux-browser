package com.termux.browser.ai

/**
 * M9 second provider. Same safety machinery as ChatGPT (M8): JSON-quoted
 * prompt bridge with finally-cleanup, fail-closed health gate, generation
 * invalidation. DeepSeek-specific identity comes from the marker check:
 * a generic textarea match on the right host is NOT sufficient to submit.
 */
class DeepSeekAdapter : SiteAdapter {
    override val id = "deepseek"
    override val hosts = setOf("chat.deepseek.com")

    companion object {
        const val VERSION = "m9-1"

        const val MARKER_SELECTOR = "[class*='ds-']"

        val PROMPT_CANDIDATES = listOf(
            "textarea[placeholder*='Message']",
            "textarea",
            "div[contenteditable='true']",
            "[contenteditable='true']"
        )
        // DeepSeek renders zero <button> elements: the send control is the
        // empty div adjacent to the composer textarea (CSS-icon button).
        // Structural candidate first; semantic button fallbacks after.
        // Live-verified: the send control is the rightmost role=button in
        // the composer row. LAST: picks the last match instead of first.
        val SUBMIT_CANDIDATES = listOf(
            "LAST:div:has(> textarea) + div div[role='button']",
            "div:has(> textarea) + div",
            "button[type='submit']",
            "button[aria-label*='Send']",
            "[data-testid='send-button']"
        )
        val MESSAGE_CANDIDATES = listOf(
            "[class*='ds-markdown']",
            "[class*='ds-message']",
            ".markdown",
            "article"
        )
        val STOP_CANDIDATES = listOf(
            "button[aria-label*='Stop']",
            "[class*='ds-stop']"
        )
    }

    override fun detect(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return host in hosts || hosts.any { host.endsWith(".$it") }
    }

    override fun selectors(): SelectorSet = SelectorSet(
        prompt = PROMPT_CANDIDATES,
        submit = SUBMIT_CANDIDATES,
        messages = MESSAGE_CANDIDATES,
        stop = STOP_CANDIDATES
    )

    override fun markerSelector(): String = MARKER_SELECTOR

    /**
     * Structural send anchor: the rightmost role=button in the composer
     * row (live-verified the 34x34 send arrow twice). Pre-tap validation
     * requires a learned selector to resolve to this node.
     */
    override fun sendAnchor(): SendAnchor =
        SendAnchor("div:has(> textarea) + div div[role='button']", last = true)

    override fun snapshotScript(): String {
        return buildSnapshotScript(
            PROMPT_CANDIDATES, MESSAGE_CANDIDATES, STOP_CANDIDATES, SUBMIT_CANDIDATES,
            MARKER_SELECTOR
        )
    }

    override fun health(snapshot: Snapshot, url: String): AdapterHealth {
        val recognized = detect(url)
        // Provider-specific identity: the DeepSeek marker must be present.
        // A generic textarea on the right host is never enough to submit.
        val identity = recognized && snapshot.markerFound
        val enterCapable = snapshot.composerKind == "textarea" ||
            snapshot.composerKind == "contenteditable"
        return AdapterHealth(
            site = id,
            recognized = recognized,
            promptInput = identity && snapshot.promptFound,
            submitControl = identity && (snapshot.submitFound || enterCapable),
            responseContainer = identity && snapshot.messageCount > 0,
            loginState = if (snapshot.loginRequired) "logged_out" else "unknown",
            adapterVersion = VERSION,
            host = url.substringAfter("://", url).substringBefore('/'),
            uiVariant = "candidate-probe"
        )
    }
}
