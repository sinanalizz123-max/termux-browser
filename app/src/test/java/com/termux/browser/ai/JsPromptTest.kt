package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsPromptTest {

    @Test
    fun `arbitrary characters are quoted never concatenated`() {
        val prompt = "Say \"hi\"\nnew line\ttab emoji \uD83D\uDE00 </script><img src=x onerror=alert(1)>"
        val quoted = JsPrompt.quotedPrompt(prompt)
        // The JSON literal round-trips the exact text: quoting, not code.
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""))
        assertFalse(quoted.contains("\n"))
        assertEquals(
            prompt,
            kotlinx.serialization.json.Json.decodeFromString<String>(quoted)
        )
        val script = JsPrompt.submitScript(
            quoted,
            JsPrompt.quotedStringList(listOf("textarea")),
            JsPrompt.quotedStringList(listOf("button"))
        )
        assertTrue(script.contains(quoted))
        assertFalse(script.contains(prompt))
    }

    @Test
    fun `submit script always cleans the bridge`() {
        val script = JsPrompt.submitScript("\"hi\"", "[]", "[]")
        assertTrue(script.contains("finally"))
        assertTrue(script.contains("delete window.__tbPrompt"))
        // Exactly two writes: the single pre-submission assignment and the
        // single finally-block cleanup. No other prompt sink exists.
        assertEquals(2, "window.__tbPrompt=".toRegex().findAll(script).count())
    }

    @Test
    fun `enter fallback reports its method`() {
        val script = JsPrompt.submitScript("\"hi\"", "[\"textarea\"]", "[]")
        // No submit candidates: falls back to the composer's native Enter.
        assertTrue(script.contains("KeyboardEvent"))
        assertTrue(script.contains("R.method='enter'"))
        assertTrue(script.contains("R.method='button'"))
        // Bridge cleanup still unconditional.
        assertTrue(script.contains("delete window.__tbPrompt"))
    }

    @Test
    fun `candidate indices are baked as integers`() {
        val script = JsPrompt.submitScript("\"hi\"", "[\"a\"]", "[\"b\"]", 2, 1)
        assertTrue(script.contains("k!=2"))
        assertTrue(script.contains("j!=1"))
    }

    @Test
    fun `clear script deletes the bridge`() {
        assertTrue(JsPrompt.CLEAR_SCRIPT.contains("delete window.__tbPrompt"))
    }

    @Test
    fun `submit script uses native setter for framework inputs`() {
        val script = JsPrompt.submitScript("\"hi\"", "[\"textarea\"]", "[\"button\"]")
        // React/Vue-controlled inputs ignore direct value assignment.
        assertTrue(script.contains("getOwnPropertyDescriptor"))
        assertTrue(script.contains(".set;") || script.contains(".set("))
        assertTrue(script.contains("keypress"))
        assertTrue(script.contains("keyup"))
    }

    @Test
    fun `submit script handles textarea and contenteditable composers`() {
        val script = JsPrompt.submitScript("\"hi\"", "[\"textarea\"]", "[\"button\"]")
        // Textarea/input branch: native-setter assignment plus input/change events.
        assertTrue(script.contains("setter.call(el,txt)"))
        assertTrue(script.contains("new Event('input'"))
        // Contenteditable branch: replaces existing DOM text.
        assertTrue(script.contains("pe.isContentEditable"))
        assertTrue(script.contains("pe.textContent=text"))
        // Readonly elements are reported, never written blindly.
        assertTrue(script.contains("prompt-readonly"))
    }
}
