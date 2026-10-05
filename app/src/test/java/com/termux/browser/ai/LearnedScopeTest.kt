package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedScopeTest {

    private fun store(): LearnedStore {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "tb-scope-${System.nanoTime()}"
        )
        dir.mkdirs()
        return LearnedStore(dir)
    }

    @Test
    fun `page context is the first route segment`() {
        assertEquals("", pageContext("https://chat.deepseek.com/"))
        assertEquals("", pageContext("https://chat.deepseek.com"))
        assertEquals("a", pageContext("https://chat.deepseek.com/a/chat/s/d460b133-id"))
        assertEquals("c", pageContext("https://chatgpt.com/c/123?x=1"))
    }

    @Test
    fun `scoped teachings are isolated by route`() {
        val store = store()
        store.putControl("chat.deepseek.com", "send", "div.home-send", "")
        store.putControl("chat.deepseek.com", "send", "div.chat-send", "a")
        assertEquals("div.home-send", store.getControl("chat.deepseek.com", "send", ""))
        assertEquals("div.chat-send", store.getControl("chat.deepseek.com", "send", "a"))
        // Unknown route sees nothing, never a foreign route's selector.
        assertNull(store.getControl("chat.deepseek.com", "send", "other"))
    }

    @Test
    fun `legacy host-level teaching never serves scoped lookups`() {
        val store = store()
        store.put("example.com", "div.legacy")
        assertEquals("div.legacy", store.getControl("example.com", "send"))
        assertNull(store.getControl("example.com", "send", "page"))
    }

    @Test
    fun `stale marking hides the teaching`() {
        val store = store()
        store.putControl("example.com", "send", "div.send", "page")
        assertEquals("div.send", store.getControl("example.com", "send", "page"))
        store.markStale("example.com", "send", "page")
        assertNull(store.getControl("example.com", "send", "page"))
    }

    @Test
    fun `validator accepts a visible button-shaped anchor match`() {
        assertTrue(
            SendTargetValidator.isSendTarget(
                found = true, tag = "DIV", role = "button", visible = true,
                w = 34.0, h = 34.0, anchorFound = true, matchesAnchor = true
            )
        )
    }

    @Test
    fun `validator rejects a stale teaching pointing at another control`() {
        // Toggle-like: anchor resolves elsewhere.
        assertFalse(
            SendTargetValidator.isSendTarget(
                found = true, tag = "DIV", role = "button", visible = true,
                w = 34.0, h = 34.0, anchorFound = true, matchesAnchor = false
            )
        )
        // Not button-shaped at all.
        assertFalse(
            SendTargetValidator.isSendTarget(
                found = true, tag = "DIV", role = "", visible = true,
                w = 34.0, h = 34.0, anchorFound = false, matchesAnchor = false
            )
        )
        // Hidden or absurd geometry.
        assertFalse(
            SendTargetValidator.isSendTarget(
                found = true, tag = "BUTTON", role = "", visible = false,
                w = 34.0, h = 34.0, anchorFound = false, matchesAnchor = false
            )
        )
        assertFalse(
            SendTargetValidator.isSendTarget(
                found = true, tag = "BUTTON", role = "", visible = true,
                w = 400.0, h = 200.0, anchorFound = false, matchesAnchor = false
            )
        )
    }

    @Test
    fun `validation script carries structure only`() {
        val script = JsPrompt.validateSendScript("\"div.send\"", "\"div.anchor\"", false)
        assertTrue(script.contains("__tbValidate"))
        assertTrue(script.contains("matchesAnchor"))
        // No page content, names, values, or URLs may leave the page.
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
        assertFalse(script.contains(".value"))
        assertFalse(script.contains("aria-label"))
    }
}
