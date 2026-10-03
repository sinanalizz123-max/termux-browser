package com.termux.browser

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /** Robolectric has no Android Keystore provider; activity takes a fake. */
    private class XorCrypto : TokenCrypto {
        override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> =
            ByteArray(12) { 3 } to plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private fun startActivity() =
        Robolectric.buildActivity(BrowserActivity::class.java).setup().get()

    @Before
    fun setUp() {
        BrowserActivity.testCrypto = XorCrypto()
    }

    @After
    fun tearDown() {
        BrowserActivity.testCrypto = null
    }

    @Test
    fun `secure webview baseline is configured`() {
        val activity = startActivity()
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
