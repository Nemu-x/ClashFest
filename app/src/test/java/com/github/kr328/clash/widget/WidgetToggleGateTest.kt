package com.github.kr328.clash.widget

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetToggleGateTest {
    @Test
    fun repeatedTapCannotReverseAnUnfinishedConnection() {
        val gate = WidgetToggleGate()
        assertTrue(gate.tryBegin(1000L))
        assertFalse(gate.tryBegin(1500L))
        assertFalse(gate.tryBegin(15000L))
        gate.finish()
        assertTrue(gate.tryBegin(16000L))
    }

    @Test
    fun fastCompletedCommandStillRejectsADoubleTap() {
        val gate = WidgetToggleGate()
        assertTrue(gate.tryBegin(1000L))
        gate.finish()
        assertFalse(gate.tryBegin(1100L))
        assertTrue(gate.tryBegin(1600L))
    }

    @Test
    fun failedOrCancelledCommandDoesNotLeaveTheWidgetLocked() {
        val gate = WidgetToggleGate()
        assertTrue(gate.tryBegin(0L))
        gate.finish()
        assertTrue(gate.tryBegin(600L))
    }

    @Test
    fun permissionDialogCanResumeAfterAnActivityRecreation() {
        val gate = WidgetToggleGate()
        assertTrue(gate.tryBegin(1000L))
        assertFalse(gate.tryBegin(1100L, restoring = true))
        gate.finish()
        assertTrue(gate.tryBegin(1100L, restoring = true))
    }
}
