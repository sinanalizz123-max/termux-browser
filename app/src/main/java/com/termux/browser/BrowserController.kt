package com.termux.browser

/**
 * Sole owner of WebView mutation. HTTP handlers (M2+) and UI buttons never
 * touch the WebView directly; they go through here so at most one automation
 * operation owns the page at a time and stale callbacks are discarded via
 * generation IDs.
 */
interface PageHost {
    fun loadUrl(url: String)
    fun stopLoading()
    fun goBack()
    fun goForward()
    fun reload()
    fun currentUrl(): String?

    /** Runs a static extraction script; returns its JSON string or null. */
    suspend fun evalJs(script: String): String?
}

class BrowserController(
    private val host: PageHost,
    private val policy: ControlPolicy,
    private val log: ActivityLog,
    private val announce: (String) -> Unit
) {
    private var activeGeneration: Int = policy.generation
    private var pendingUrl: String? = null
    private var lastOpenedUrl: String? = null

    fun open(input: String, source: String): Boolean {
        val url = UrlPolicy.normalize(input)
        if (url == null) {
            announce("Rejected navigation: unsupported input.")
            log.add(source, "open_rejected", input.take(200))
            return false
        }
        activeGeneration = policy.generation
        pendingUrl = url
        lastOpenedUrl = url
        host.loadUrl(url)
        log.add(source, "open", url)
        announce("[$source] Opening $url")
        return true
    }

    /**
     * Called by the WebView client when a navigation begins. Returns true
     * when the navigation belongs to a controller-issued open; false means
     * it is a user/manual navigation that must invalidate automation.
     */
    fun claimPending(url: String): Boolean {
        if (pendingUrl == url) {
            pendingUrl = null
            return true
        }
        return false
    }

    fun back(source: String) {
        host.goBack()
        log.add(source, "back", host.currentUrl() ?: "")
    }

    fun forward(source: String) {
        host.goForward()
        log.add(source, "forward", host.currentUrl() ?: "")
    }

    fun reload(source: String) {
        host.reload()
        log.add(source, "reload", host.currentUrl() ?: "")
    }

    fun userNavigated(url: String) {
        policy.onUserNavigation()
        activeGeneration = policy.generation
        pendingUrl = null
        lastOpenedUrl = url
        log.add("user", "navigate", url)
        announce("[user] Navigated to $url")
    }

    fun stop(source: String) {
        host.stopLoading()
        policy.onStop()
        pendingUrl = null
        log.add(source, "stop", host.currentUrl() ?: "")
        announce("[$source] Stopped. Queued automation cleared.")
    }

    fun onPageFinished(url: String) {
        // Stale when the session generation moved on (takeover/stop) or the
        // finished URL is not the currently expected page.
        if (activeGeneration != policy.generation || url != lastOpenedUrl) {
            log.add("system", "stale_callback_ignored", url)
            return
        }
        log.add("system", "page_ready", url)
        announce("Page ready: $url")
    }

    fun onPageError(url: String, description: String) {
        log.add("system", "page_error", "$url :: ${description.take(200)}")
        announce("Page error: $description")
    }
}
