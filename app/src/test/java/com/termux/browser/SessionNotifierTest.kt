package com.termux.browser

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
class SessionNotifierTest {

    private fun notifier(): SessionNotifier {
        val app = RuntimeEnvironment.getApplication()
        return SessionNotifier(app)
    }

    @Test
    fun `show posts an ongoing state-only notification`() {
        val app = RuntimeEnvironment.getApplication()
        notifier().show("Termux automation active")
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        val notification = posted[0]
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("Termux automation active"))
        assertEquals(2, notification.actions.size)
    }

    @Test
    fun `cancel removes the notification`() {
        val app = RuntimeEnvironment.getApplication()
        val session = notifier()
        session.show("working")
        session.cancel()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
