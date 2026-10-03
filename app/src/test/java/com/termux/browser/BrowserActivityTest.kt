package com.termux.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * M1 activity lifecycle: secure WebView baseline survives recreation and the
 * restored page becomes the authoritative status (no stale blank state).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class BrowserActivityTest {

    @Test
    fun `secure webview baseline is configured`() {
        val activity = Robolectric.buildActivity(BrowserActivity::class.java)
            .setup().get()
        val settings = activity.webView.settings
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.domStorageEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
    }

    @Test
    fun `recreation restores the visible page`() {
        val controller = Robolectric.buildActivity(BrowserActivity::class.java).setup()
        val activity = controller.get()
        activity.controller.open("https://example.com", "user")

        val recreated = controller.recreate().get()

        assertTrue(recreated.statusView.text.contains("example.com"))
    }
}
