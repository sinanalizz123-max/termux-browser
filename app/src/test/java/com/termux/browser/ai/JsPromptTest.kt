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
        // The JSON literal round-trips the exact text.
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""))
        // Raw control characters and markup never appear literally.
        assertFalse(quoted.contains("\n"))
        assertFalse(quoted.contains("</script>"))
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
        // Only one assignment point, immediately before submission logic.
        assertEquals(1, "window.__tbPrompt=".toRegex().findAll(script).count())
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
}
