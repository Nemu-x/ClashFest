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

    @Test
    fun everyStateSetsItsOwnIconAndBackground() {
        // The widget is one frame now: each render must set both, or a stale icon stays.
        State.entries.forEach {
            assertTrue(it.icon != 0, "$it has no icon")
            assertTrue(it.background != 0, "$it has no background")
        }
        assertEquals(com.github.kr328.clash.service.R.drawable.widget_mikan, State.On.icon)
    }
}
