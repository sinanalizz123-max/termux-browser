package com.termux.browser

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class ControlReceiverTest {

    @After
    fun tearDown() {
        ControlPlane.onPauseRequested = null
        ControlPlane.onResumeRequested = null
        ControlPlane.onStopRequested = null
    }

    private fun send(action: String) {
        val receiver = ControlReceiver()
        receiver.onReceive(
            RuntimeEnvironment.getApplication(),
            Intent(action)
        )
    }

    @Test
    fun `stop pause and resume reach the control plane`() {
        var stopped = false
        var paused = false
        var resumed = false
        ControlPlane.onStopRequested = { stopped = true }
        ControlPlane.onPauseRequested = { paused = true }
        ControlPlane.onResumeRequested = { resumed = true }
        send(ControlReceiver.ACTION_STOP)
        send(ControlReceiver.ACTION_PAUSE)
        send(ControlReceiver.ACTION_RESUME)
        assertTrue(stopped && paused && resumed)
    }

    @Test
    fun `open launches the browser activity`() {
        send(ControlReceiver.ACTION_OPEN)
        val next = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(
            BrowserActivity::class.java.name,
            next.component?.className
        )
    }

    @Test
    fun `receiver intents are explicit to the receiver`() {
        val app = RuntimeEnvironment.getApplication()
        for (intent in listOf(
            ControlReceiver.stopIntent(app),
            ControlReceiver.pauseIntent(app),
            ControlReceiver.resumeIntent(app),
            ControlReceiver.openIntent(app)
        )) {
            assertEquals(ControlReceiver::class.java.name, intent.component?.className)
        }
    }
}
