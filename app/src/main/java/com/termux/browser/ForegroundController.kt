package com.termux.browser

/**
 * Foreground-service policy (pure, unit-tested).
 *
 * FGS = control plane up (server running or automation executing) AND
 * activity stopped/backgrounded. This keeps Termux commands working while
 * the app is backgrounded. Foreground never holds an FGS.
 * The FGS is started during the foreground-to-background transition
 * (onPause with an active control plane), never from a fully backgrounded
 * state, per the Android 12+ background-start restriction.
 */
class ForegroundController {

    sealed interface Action {
        data object StartFgs : Action
        data object StopFgs : Action
        data object None : Action
    }

    var automationActive: Boolean = false
        private set
    var serverActive: Boolean = false
        private set
    var activityVisible: Boolean = true
        private set

    private fun controlActive(): Boolean = automationActive || serverActive

    fun onAutomationChanged(active: Boolean): Action {
        automationActive = active
        return evaluate()
    }

    fun onServerChanged(active: Boolean): Action {
        serverActive = active
        return evaluate()
    }

    fun onVisibilityChanged(visible: Boolean): Action {
        val wasVisible = activityVisible
        activityVisible = visible
        // Resume always stops the FGS (control plane keeps running); a
        // repeated visible signal with no transition changes nothing.
        if (visible && !wasVisible) return Action.StopFgs
        return evaluate()
    }

    private fun evaluate(): Action = when {
        controlActive() && !activityVisible -> Action.StartFgs
        !controlActive() -> Action.StopFgs
        else -> Action.None
    }
}
