package com.termux.browser.ai

/**
 * M8 first provider adapter. ChatGPT web UI, semantic-first candidate sets.
 * Candidates are ordered data; the registry never executes them blindly and
 * the health gate fails closed when prompt/submit cannot be confirmed.
 */
class ChatGPTAdapter : SiteAdapter {
    override val id = "chatgpt"
    override val hosts = setOf("chatgpt.com", "chat.openai.com")

    companion object {
        const val VERSION = "m8-1"

        val PROMPT_CANDIDATES = listOf(
            "#prompt-textarea",
            "div[contenteditable='true'][data-testid]",
            "textarea[name='prompt']",
            "textarea[placeholder*='Message']",
            "div[contenteditable='true']",
            "textarea"
        )
        val SUBMIT_CANDIDATES = listOf(
            "button[data-testid='send-button']",
            "button[aria-label*='Send']",
            "button[data-composer-submit]",
            "button[type='submit']"
        )
        val MESSAGE_CANDIDATES = listOf(
            "[data-message-author-role='assistant']",
            "[data-message-role='assistant']",
            ".markdown"
        )
        val STOP_CANDIDATES = listOf(
            "button[data-testid='stop-button']",
            "button[aria-label*='Stop']"
        )
    }

    override fun detect(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return host in hosts || hosts.any { host == it || host.endsWith(".$it") }
    }

    override fun selectors(): SelectorSet = SelectorSet(
        prompt = PROMPT_CANDIDATES,
        submit = SUBMIT_CANDIDATES,
        messages = MESSAGE_CANDIDATES,
        stop = STOP_CANDIDATES
    )

    /**
     * Static snapshot script with baked candidate lists (data, zero params).
     * Reports candidate presence so health() can fail closed, plus the usual
     * message/generating observation and a login-wall heuristic.
     */
    override fun snapshotScript(): String {
        return buildSnapshotScript(
            PROMPT_CANDIDATES, MESSAGE_CANDIDATES, STOP_CANDIDATES, SUBMIT_CANDIDATES
        )
    }

    override fun health(snapshot: Snapshot, url: String): AdapterHealth {
        val recognized = detect(url)
        // Submit control: an explicit submit button, or a composer whose
        // native affordance is Enter-to-send (its own confirmed element).
        val enterCapable = snapshot.composerKind == "textarea" ||
            snapshot.composerKind == "contenteditable"
        return AdapterHealth(
            site = id,
            recognized = recognized,
            promptInput = recognized && snapshot.promptFound,
            submitControl = recognized && (snapshot.submitFound || enterCapable),
            responseContainer = recognized && snapshot.messageCount > 0,
            loginState = if (snapshot.loginRequired) "logged_out" else "unknown",
            adapterVersion = VERSION,
            host = url.substringAfter("://", url).substringBefore('/'),
            uiVariant = "candidate-probe"
        )
    }
}
