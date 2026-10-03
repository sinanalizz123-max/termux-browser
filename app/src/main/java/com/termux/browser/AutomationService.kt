package com.termux.browser

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * M6 automation foreground service. Exists ONLY while automation executes
 * with the activity backgrounded. Started from the foreground-to-background
 * transition, stopped on automation end or activity resume. START_NOT_STICKY:
 * a killed automation session never resurrects itself.
 */
class AutomationService : Service() {

    companion object {
        const val EXTRA_STATE = "com.termux.browser.EXTRA_STATE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.getStringExtra(EXTRA_STATE) ?: "Automation active"
        val notification = SessionNotifier(this).show(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SessionNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(SessionNotifier.NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }
}
