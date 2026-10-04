package com.termux.browser

import java.net.URLEncoder

/**
 * M0 URL policy: Termux (and the address bar) may only navigate HTTP(S).
 * Everything else is rejected with a user-facing message, never loaded.
 */
object UrlPolicy {

    const val SEARCH_ENGINE_URL = "https://duckduckgo.com/?q="

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    fun isAllowed(url: String): Boolean {
        val match = SCHEME.find(url.trim())
        if (match == null) return true // bare host or search text; normalized below
        val scheme = match.groupValues[1].lowercase()
        return scheme == "http" || scheme == "https"
    }

    /**
     * Normalize address-bar input to a loadable https URL, a search URL, or
     * null when the input must be rejected.
     */
    /**
     * Same-page check tolerant of WebView trailing-slash normalization
     * (open "https://example.com" finishes as "https://example.com/").
     * Null-safe: a null side only matches null.
     */
    fun samePage(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.trimEnd('/') == b.trimEnd('/')
    }

    /**
     * Display form for banners, logs, notifications, and API status: scheme
     * + host + path only, capped in length. OAuth and session tokens live
     * in query parameters and must never reach user-visible surfaces.
     */
    fun forDisplay(url: String?, maxChars: Int = 160): String {
        if (url.isNullOrBlank()) return ""
        val noFragment = url.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val trimmed = noQuery.trim()
        return if (trimmed.length > maxChars) {
            trimmed.take(maxChars) + "…"
        } else {
            trimmed
        }
    }

    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val match = SCHEME.find(text)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            return text
        }
        if (text.contains(' ') || !text.contains('.')) {
            return SEARCH_ENGINE_URL + URLEncoder.encode(text, "UTF-8")
        }
        return "https://$text"
    }
}
