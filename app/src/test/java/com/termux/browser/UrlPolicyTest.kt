package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun `https urls are allowed`() {
        assertTrue(UrlPolicy.isAllowed("https://example.com"))
        assertEquals("https://example.com", UrlPolicy.normalize("https://example.com"))
    }

    @Test
    fun `http urls are allowed`() {
        assertTrue(UrlPolicy.isAllowed("http://example.com"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        for (input in listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "intent://example.com#Intent;end",
            "blob:https://example.com/1",
            "content://media/external/1"
        )) {
            assertFalse("must reject $input", UrlPolicy.isAllowed(input))
            assertNull("must not normalize $input", UrlPolicy.normalize(input))
        }
    }

    @Test
    fun `bare host gets https prefix`() {
        assertEquals("https://example.com", UrlPolicy.normalize("example.com"))
    }

    @Test
    fun `plain text becomes a search url`() {
        val url = UrlPolicy.normalize("best offline maps")
        assertTrue(url!!.startsWith(UrlPolicy.SEARCH_ENGINE_URL))
        assertTrue(url.contains("best"))
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(UrlPolicy.normalize("   "))
    }

    @Test
    fun `same page ignores a single trailing slash`() {
        assertTrue(UrlPolicy.samePage("https://example.com", "https://example.com/"))
        assertTrue(UrlPolicy.samePage("https://example.com/", "https://example.com/"))
        assertFalse(UrlPolicy.samePage("https://example.com/a", "https://example.com/b"))
        assertFalse(UrlPolicy.samePage(null, "https://example.com/"))
        assertTrue(UrlPolicy.samePage(null, null))
    }

    @Test
    fun `display form strips tokens and caps length`() {
        assertEquals(
            "https://accounts.google.com/v3/signin/identifier",
            UrlPolicy.forDisplay("https://accounts.google.com/v3/signin/identifier?opparams=secret&state=xyz#frag")
        )
        assertEquals("", UrlPolicy.forDisplay(null))
        assertEquals("", UrlPolicy.forDisplay("   "))
        val long = "https://example.com/" + "p".repeat(500)
        val shown = UrlPolicy.forDisplay(long)
        assertTrue(shown.length <= 161)
        assertFalse(shown.contains("p".repeat(161)))
    }
}
