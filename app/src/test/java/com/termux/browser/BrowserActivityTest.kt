package com.termux.browser

import android.os.Looper
import android.os.SystemClock
import androidx.core.view.GravityCompat
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        // Ephemeral ports: activity servers must never fight the fixed
        // port (or each other) inside one test JVM.
        BrowserActivity.testPortOverride = 0
    }

    @After
    fun tearDown() {
        BrowserActivity.testCrypto = null
        BrowserActivity.testPortOverride = null
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

    @Test
    fun `open through the real controller path loads the webview`() = runBlocking {
        // Robolectric never resolves real WebView JavaScript, so the
        // activity-level path is verified with open (no JS needed): arbiter
        // -> controller -> activity PageHost -> real WebView URL state.
        val activity = startActivity()
        val results = ResultStore()
        val ui = object : UiRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        val arbiter = CommandArbiter(
            this, ui, activity, activity.controller, ControlPolicy(), results
        )
        try {
            val submitted = arbiter.submit(BrowserCommand.Open("https://example.com", "termux"))
            val id = (submitted as SubmitResult.Accepted).commandId
            var stored: StoredResult? = null
            val deadline = System.currentTimeMillis() + 15000
            while (stored == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(100)
                stored = results.get(id)
            }
            assertTrue(stored != null)
            assertTrue(stored!!.body.contains("completed"))
            assertEquals("https://example.com", activity.webView.url)
        } finally {
            arbiter.close()
        }
    }

    @Test
    fun `background automation starts service, resume stops it`() {
        val controller = Robolectric.buildActivity(BrowserActivity::class.java).setup()
        val activity = controller.get()
        activity.automationActiveOverride = true

        controller.pause()
        val started = shadowOf(activity.application).nextStartedService
        assertEquals(AutomationService::class.java.name, started.component?.className)

        controller.resume()
        val stopped = shadowOf(activity.application).nextStoppedService
        assertEquals(AutomationService::class.java.name, stopped.component?.className)
    }

    @Test
    fun `menu button opens and closes the activity drawer`() {
        val activity = startActivity()
        assertFalse(
            activity.drawerLayout.isDrawerOpen(GravityCompat.START)
        )
        activity.menuButton.performClick()
        assertTrue(
            activity.drawerLayout.isDrawerOpen(GravityCompat.START)
        )
        activity.menuButton.performClick()
        assertFalse(
            activity.drawerLayout.isDrawerOpen(GravityCompat.START)
        )
    }

    @Test
    fun `content respects system bar insets`() {
        val activity = startActivity()
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(0, 60, 0, 40)
            )
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
            activity.contentView, insets
        )
        assertEquals(60, activity.contentView.paddingTop)
        assertEquals(40, activity.contentView.paddingBottom)
    }

    @Test
    fun `touch announces user browsing in the status banner`() {
        val activity = startActivity()
        val now = SystemClock.uptimeMillis()
        activity.webView.dispatchTouchEvent(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        )
        assertTrue(activity.statusView.text.contains("User browsing"))
    }
}
