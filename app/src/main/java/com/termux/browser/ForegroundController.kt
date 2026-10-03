package com.termux.browser

/**
 * M6 foreground-service policy (pure, unit-tested).
 *
 * FGS = automation actively executing AND activity stopped/backgrounded.
 * Foreground automation never holds an FGS. Idle never holds an FGS.
 * The FGS is started during the foreground-to-background transition
 * (onPause with active automation), never from a fully backgrounded state,
 * per the Android 12+ background-start restriction.
 */
class ForegroundController {

    sealed interface Action {
        data object StartFgs : Action
        data object StopFgs : Action
        data object None : Action
    }

    var automationActive: Boolean = false
        private set
    var activityVisible: Boolean = true
        private set

    fun onAutomationChanged(active: Boolean): Action {
        automationActive = active
        return evaluate()
    }

    fun onVisibilityChanged(visible: Boolean): Action {
        val wasVisible = activityVisible
        activityVisible = visible
        // Resume always stops the FGS (automation continues); a repeated
        // visible signal with no transition changes nothing.
        if (visible && !wasVisible) return Action.StopFgs
        return evaluate()
    }

    private fun evaluate(): Action = when {
        automationActive && !activityVisible -> Action.StartFgs
        !automationActive -> Action.StopFgs
        else -> Action.None
    }
}
