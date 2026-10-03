package com.termux.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * M6 session notification: ongoing, state-only, actionable. Never carries the
 * token, page content, or AI conversations — only the automation state text
 * already visible in the app banner.
 */
class SessionNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "termux_browser_automation"
        const val NOTIFICATION_ID = 41
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Browser automation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows Termux browser automation state."
                }
            )
        }
    }

    fun show(stateText: String): Notification {
        ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getBroadcast(
            context, 1, ControlReceiver.openIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            context, 2, ControlReceiver.stopIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Termux Browser")
            .setContentText(stateText.take(200))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "Open", open).build()
            )
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .build()
        // Post directly: the same notification object feeds startForeground,
        // so banner and service always agree.
        manager?.notify(NOTIFICATION_ID, notification)
        return notification
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
