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
