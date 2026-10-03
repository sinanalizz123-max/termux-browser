package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPolicyTest {

    @Test
    fun `stop clears automation and bumps generation`() {
        val policy = ControlPolicy()
        policy.onAutomationStart()
        val gen = policy.generation
        policy.onStop()
        assertEquals(ControlState.STOPPED, policy.state)
        assertEquals(gen + 1, policy.generation)
        assertFalse(policy.isCurrent(gen))
        assertTrue(policy.isCurrent(gen + 1))
    }

    @Test
    fun `pause preserves generation`() {
        val policy = ControlPolicy()
        policy.onAutomationStart()
        val gen = policy.generation
        policy.onPause()
        assertEquals(ControlState.PAUSED, policy.state)
        assertEquals(gen, policy.generation)
    }

    @Test
    fun `user takeover cancels active run but keeps queue for resume`() {
        val policy = ControlPolicy()
        policy.onAutomationStart()
        val gen = policy.generation
        policy.onUserInput()
        assertEquals(ControlState.USER_TAKEOVER, policy.state)
        assertEquals(gen + 1, policy.generation)
        policy.onResume()
        assertEquals(ControlState.AUTOMATION_RUNNING, policy.state)
        assertEquals(gen + 1, policy.generation)
    }

    @Test
    fun `user navigation invalidates in-flight automation`() {
        val policy = ControlPolicy()
        policy.onAutomationStart()
        val gen = policy.generation
        policy.onUserNavigation()
        assertEquals(ControlState.USER_TAKEOVER, policy.state)
        assertFalse(policy.isCurrent(gen))
    }

    @Test
    fun `manual browsing never enters takeover`() {
        val policy = ControlPolicy()
        policy.onUserInput()
        policy.onUserNavigation()
        assertEquals(ControlState.IDLE, policy.state)
        assertEquals(0, policy.generation)
    }
}
