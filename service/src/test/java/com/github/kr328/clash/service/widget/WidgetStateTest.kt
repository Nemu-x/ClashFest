package com.github.kr328.clash.service.widget

import com.github.kr328.clash.service.widget.SpeedWidgetRenderer.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetStateTest {
    @Test
    fun ordinaryToggleNeverNeedsAnActivity() {
        assertFalse(State.Off.requiresActivity)
        assertFalse(State.On.requiresActivity)
        assertTrue(State.PermissionRequired.requiresActivity)
    }

    @Test
    fun permissionPromptOnlyAppliesToAStoppedVpn() {
        assertEquals(State.PermissionRequired, State.fromStatus(false, false, permissionRequired = true))
        assertEquals(State.On, State.fromStatus(true, true, permissionRequired = true))
        assertEquals(State.Starting, State.fromStatus(true, false, permissionRequired = true))
    }

    @Test
    fun unloadedProfileDoesNotShowAConnectedVpn() {
        assertEquals(State.Starting, State.fromStatus(serviceRunning = true, profileLoaded = false))
    }

    @Test
    fun staleProfileDoesNotShowAStoppedVpnAsConnected() {
        assertEquals(State.Off, State.fromStatus(serviceRunning = false, profileLoaded = true))
    }

    @Test
    fun runningVpnWithALoadedProfileShowsConnected() {
        assertEquals(State.On, State.fromStatus(serviceRunning = true, profileLoaded = true))
    }
}
