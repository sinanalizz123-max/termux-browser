package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAdapterTest {

    private val adapter = DeepSeekAdapter()

    @Test
    fun `detects deepseek host`() {
        assertTrue(adapter.detect("https://chat.deepseek.com/"))
        assertTrue(adapter.detect("https://chat.deepseek.com/a/chat/s/123"))
        assertFalse(adapter.detect("https://chatgpt.com/"))
        assertFalse(adapter.detect("https://example.com/"))
    }

    @Test
    fun `marker plus controls pass the gate`() {
        val health = adapter.health(
            Snapshot(
                messageCount = 1,
                lastText = "old",
                promptFound = true,
                submitFound = true,
                markerFound = true
            ),
            "https://chat.deepseek.com/"
        )
        assertTrue(health.recognized)
        assertTrue(health.promptInput)
        assertTrue(health.submitControl)
    }

    @Test
    fun `right host but no marker fails closed`() {
        // A generic textarea match on the right host must NOT authorize
        // submission when DeepSeek identity is absent.
        val health = adapter.health(
            Snapshot(promptFound = true, submitFound = true, markerFound = false),
            "https://chat.deepseek.com/"
        )
        assertTrue(health.recognized)
        assertFalse(health.promptInput)
        assertFalse(health.submitControl)
    }

    @Test
    fun `snapshot script probes the marker`() {
        val script = adapter.snapshotScript()
        assertTrue(script.contains("markerFound"))
        assertTrue(script.contains("ds-"))
        assertTrue(script.contains("promptFound"))
        assertTrue(script.contains("submitFound"))
    }

    @Test
    fun `structural send candidate leads the submit set`() {
        // Live-probed reality: zero <button> elements; the send control is
        // the empty div adjacent to the composer textarea.
        val submit = adapter.selectors().submit
        assertTrue(submit.first().contains("textarea"))
        assertTrue(submit.first().contains("+ div"))
    }

    @Test
    fun `composer candidates cover textarea and contenteditable`() {
        val joined = adapter.selectors().prompt.joinToString(" ")
        assertTrue(joined.contains("textarea"))
        assertTrue(joined.contains("contenteditable"))
    }

    @Test
    fun `production registry routes each host to its owner`() {
        val registry = com.termux.browser.ai.AdapterRegistry(
            listOf(ChatGPTAdapter(), DeepSeekAdapter(), GenericAdapter())
        )
        assertEquals("chatgpt", registry.detect("https://chatgpt.com/c/1")?.id)
        assertEquals("deepseek", registry.detect("https://chat.deepseek.com/a/chat/s/1")?.id)
        assertEquals("generic", registry.detect("https://example.com/")?.id)
    }
}
