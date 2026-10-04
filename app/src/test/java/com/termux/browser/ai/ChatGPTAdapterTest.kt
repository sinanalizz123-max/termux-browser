package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGPTAdapterTest {

    private val adapter = ChatGPTAdapter()

    @Test
    fun `detects chatgpt hosts including subdomains`() {
        assertTrue(adapter.detect("https://chatgpt.com/"))
        assertTrue(adapter.detect("https://chatgpt.com/c/123?q=1"))
        assertTrue(adapter.detect("https://chat.openai.com/"))
        assertFalse(adapter.detect("https://deepseek.com/"))
        assertFalse(adapter.detect("https://example.com/"))
    }

    @Test
    fun `candidate sets are ordered and bounded`() {
        val selectors = adapter.selectors()
        assertTrue(selectors.prompt.isNotEmpty())
        assertTrue(selectors.submit.isNotEmpty())
        assertTrue(selectors.messages.isNotEmpty())
        assertTrue(selectors.prompt.size + selectors.submit.size < 30)
    }

    @Test
    fun `snapshot script is static with baked candidates`() {
        val script = adapter.snapshotScript()
        assertTrue(script.contains("querySelector"))
        assertTrue(script.contains("prompt-textarea"))
        assertTrue(script.contains("messageCount"))
        assertTrue(script.contains("promptFound"))
    }

    @Test
    fun `healthy snapshot passes the gate`() {
        val health = adapter.health(
            Snapshot(messageCount = 2, lastText = "old", promptFound = true, submitFound = true),
            "https://chatgpt.com/c/1"
        )
        assertTrue(health.recognized)
        assertTrue(health.promptInput)
        assertTrue(health.submitControl)
    }

    @Test
    fun `enter-capable composer counts as submit control`() {
        val health = adapter.health(
            Snapshot(promptFound = true, submitFound = false, composerKind = "contenteditable"),
            "https://chatgpt.com/"
        )
        assertTrue(health.promptInput)
        assertTrue(health.submitControl)
    }

    @Test
    fun `unknown composer without button fails closed`() {
        val health = adapter.health(
            Snapshot(promptFound = true, submitFound = false, composerKind = "unknown"),
            "https://chatgpt.com/"
        )
        assertTrue(health.promptInput)
        assertFalse(health.submitControl)
    }

    @Test
    fun `missing controls fail the gate closed`() {
        val noPrompt = adapter.health(
            Snapshot(promptFound = false, submitFound = true),
            "https://chatgpt.com/"
        )
        assertFalse(noPrompt.promptInput)
        val noSubmit = adapter.health(
            Snapshot(promptFound = true, submitFound = false),
            "https://chatgpt.com/"
        )
        assertFalse(noSubmit.submitControl)
    }

    @Test
    fun `login wall is reported`() {
        val health = adapter.health(
            Snapshot(promptFound = false, loginRequired = true),
            "https://chatgpt.com/"
        )
        assertEquals("logged_out", health.loginState)
    }

    @Test
    fun `unrecognized url is not recognized`() {
        val health = adapter.health(Snapshot(), "https://example.com/")
        assertFalse(health.recognized)
        assertEquals("chatgpt", health.site)
    }
}
