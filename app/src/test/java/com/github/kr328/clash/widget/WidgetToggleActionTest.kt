package com.github.kr328.clash.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetToggleActionTest {
    @Test
    fun runningVpnCanAlwaysStopEvenIfProfileWasDeletedOrPermissionRevoked() {
        assertEquals(WidgetToggleAction.Stop, widgetToggleAction(true, false, true))
    }

    @Test
    fun selectedReadyProfileStartsSilentlyAfterPermissionWasGranted() {
        assertEquals(WidgetToggleAction.Start, widgetToggleAction(false, true, false))
    }

    @Test
    fun unreadyProfileCannotStartOrPromptForPermission() {
        assertEquals(WidgetToggleAction.ChooseProfile, widgetToggleAction(false, false, false))
        assertEquals(WidgetToggleAction.ChooseProfile, widgetToggleAction(false, false, true))
    }

    @Test
    fun firstConnectionRequiresVpnConsent() {
        assertEquals(WidgetToggleAction.GrantPermission, widgetToggleAction(false, true, true))
    }
}
