package com.github.kr328.clash.service.widget

import com.github.kr328.clash.service.widget.SpeedWidgetRenderer.State
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetStateTest {
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
