package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundControllerTest {

    @Test
    fun `idle never holds a foreground service`() {
        val fgs = ForegroundController()
        assertTrue(fgs.onVisibilityChanged(false) is ForegroundController.Action.StopFgs)
        assertTrue(fgs.onVisibilityChanged(true) is ForegroundController.Action.StopFgs)
    }

    @Test
    fun `foreground automation holds no foreground service`() {
        val fgs = ForegroundController()
        assertTrue(fgs.onAutomationChanged(true) is ForegroundController.Action.None)
    }

    @Test
    fun `backgrounding with active automation starts the service`() {
        val fgs = ForegroundController()
        fgs.onAutomationChanged(true)
        assertTrue(fgs.onVisibilityChanged(false) is ForegroundController.Action.StartFgs)
    }

    @Test
    fun `resume stops the service but keeps automation flagged`() {
        val fgs = ForegroundController()
        fgs.onAutomationChanged(true)
        fgs.onVisibilityChanged(false)
        assertTrue(fgs.onVisibilityChanged(true) is ForegroundController.Action.StopFgs)
        assertEquals(true, fgs.automationActive)
    }

    @Test
    fun `automation end stops the service`() {
        val fgs = ForegroundController()
        fgs.onAutomationChanged(true)
        fgs.onVisibilityChanged(false)
        assertTrue(fgs.onAutomationChanged(false) is ForegroundController.Action.StopFgs)
    }
}
