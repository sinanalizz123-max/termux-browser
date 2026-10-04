package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAdapterTest {

    private fun bundle(
        id: String = "deepseek",
        hosts: List<String> = listOf("chat.deepseek.com"),
        version: Int = 2
    ): ValidBundle {
        return ValidBundle(
            manifest = PluginManifest(
                format = PluginFormat.FORMAT,
                id = id,
                version = version,
                minCore = 1,
                keyId = PluginFormat.KEY_ID,
                hosts = hosts,
                files = mapOf(
                    "selectors.json" to "a",
                    "health.json" to "b",
                    "plan.json" to "c"
                )
            ),
            selectors = PluginSelectors(
                prompt = listOf("textarea"),
                submit = listOf("div.send"),
                messages = listOf("[class*='ds-markdown']"),
                stop = emptyList()
            ),
            health = PluginHealth(marker = "[class*='ds-']"),
            manifestBytes = ByteArray(10),
            signature = ByteArray(256)
        )
    }

    @Test
    fun `host binding is enforced`() {
        val adapter = PluginAdapter.fromBundle(bundle())
        assertTrue(adapter.detect("https://chat.deepseek.com/"))
        assertFalse(adapter.detect("https://chatgpt.com/"))
        assertFalse(adapter.detect("https://evil-deepseek.com/"))
    }

    @Test
    fun `snapshot carries the marker and candidates`() {
        val script = PluginAdapter.fromBundle(bundle()).snapshotScript()
        assertTrue(script.contains("textarea"))
        assertTrue(script.contains("div.send"))
        assertTrue(script.contains("ds-"))
    }

    @Test
    fun `health mirrors built-in fail-closed rules`() {
        val adapter = PluginAdapter.fromBundle(bundle())
        val good = adapter.health(
            Snapshot(promptFound = true, submitFound = true, markerFound = true),
            "https://chat.deepseek.com/"
        )
        assertTrue(good.promptInput && good.submitControl)
        val noMarker = adapter.health(
            Snapshot(promptFound = true, submitFound = true, markerFound = false),
            "https://chat.deepseek.com/"
        )
        assertFalse(noMarker.promptInput || noMarker.submitControl)
        val wrongHost = adapter.health(
            Snapshot(promptFound = true, submitFound = true, markerFound = true),
            "https://chatgpt.com/"
        )
        assertFalse(wrongHost.recognized)
    }

    @Test
    fun `null marker skips only the marker check`() {
        val adapter = PluginAdapter.fromBundle(bundle().copy(health = PluginHealth(marker = null)))
        val health = adapter.health(
            Snapshot(promptFound = true, submitFound = true),
            "https://chat.deepseek.com/"
        )
        assertTrue(health.promptInput && health.submitControl)
    }
}
