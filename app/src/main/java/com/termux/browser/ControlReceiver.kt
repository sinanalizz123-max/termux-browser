package com.termux.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * M6 notification actions. Capabilities only: pause/resume/stop automation or
 * open the app. Never touches credentials, storage, or settings.
 */
object ControlPlane {
    var onPauseRequested: (() -> Unit)? = null
    var onResumeRequested: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null
}

class ControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.termux.browser.ACTION_PAUSE"
        const val ACTION_RESUME = "com.termux.browser.ACTION_RESUME"
        const val ACTION_STOP = "com.termux.browser.ACTION_STOP"
        const val ACTION_OPEN = "com.termux.browser.ACTION_OPEN"

        private fun intent(context: Context, action: String): Intent =
            Intent(context, ControlReceiver::class.java).setAction(action)

        fun pauseIntent(context: Context): Intent = intent(context, ACTION_PAUSE)
        fun resumeIntent(context: Context): Intent = intent(context, ACTION_RESUME)
        fun stopIntent(context: Context): Intent = intent(context, ACTION_STOP)
        fun openIntent(context: Context): Intent = intent(context, ACTION_OPEN)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> ControlPlane.onPauseRequested?.invoke()
            ACTION_RESUME -> ControlPlane.onResumeRequested?.invoke()
            ACTION_STOP -> ControlPlane.onStopRequested?.invoke()
            ACTION_OPEN -> {
                val open = Intent(context, BrowserActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(open)
            }
        }
    }
}
