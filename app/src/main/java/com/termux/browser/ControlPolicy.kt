package com.termux.browser

/**
 * M0/M3 control state machine (pure Kotlin, fully unit-tested).
 *
 * STOPPED clears queued automation. PAUSED preserves it. USER_TAKEOVER
 * cancels the active automation operation but preserves queued commands for
 * explicit resume. Takeover/stop/user-navigation bump [generation] so stale
 * WebView callbacks cannot complete an obsolete command.
 */
enum class ControlState {
    IDLE,
    AUTOMATION_RUNNING,
    PAUSED,
    USER_TAKEOVER,
    STOPPED
}

class ControlPolicy {

    var state: ControlState = ControlState.IDLE
        private set

    var generation: Int = 0
        private set

    fun onAutomationStart() {
        if (state == ControlState.IDLE ||
            state == ControlState.STOPPED ||
            state == ControlState.PAUSED
        ) {
            state = ControlState.AUTOMATION_RUNNING
        }
    }

    fun onUserInput() {
        if (state == ControlState.AUTOMATION_RUNNING) {
            state = ControlState.USER_TAKEOVER
            generation++
        }
    }

    fun onUserNavigation() {
        if (state == ControlState.AUTOMATION_RUNNING ||
            state == ControlState.PAUSED ||
            state == ControlState.USER_TAKEOVER
        ) {
            state = ControlState.USER_TAKEOVER
            generation++
        }
    }

    fun onPause() {
        if (state == ControlState.AUTOMATION_RUNNING ||
            state == ControlState.USER_TAKEOVER
        ) {
            state = ControlState.PAUSED
        }
    }

    fun onResume() {
        if (state == ControlState.PAUSED || state == ControlState.USER_TAKEOVER) {
            state = ControlState.AUTOMATION_RUNNING
        }
    }

    fun onStop() {
        state = ControlState.STOPPED
        generation++
    }

    fun isCurrent(generation: Int): Boolean = generation == this.generation

    fun snapshot(): Pair<Int, ControlState> = generation to state

    /** Used only to restore state after activity/process recreation. */
    fun restore(generation: Int, state: ControlState) {
        this.generation = generation
        this.state = state
    }
}
