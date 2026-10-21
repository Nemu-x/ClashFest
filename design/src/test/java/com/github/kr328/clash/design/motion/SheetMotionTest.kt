package com.github.kr328.clash.design.motion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SheetMotionTest {
    @Test fun shortPullsAtTheTopDoNotDismissThePicker() {
        assertFalse(dismissSheetAfterPull(10f, 700, 400f, 1f, false))
        assertFalse(dismissSheetAfterPull(20f, 700, 2000f, 1f, false))
    }
    @Test fun deliberatePullOrDownwardFlingDismisses() {
        assertTrue(dismissSheetAfterPull(250f, 700, 0f, 1f, false))
        assertTrue(dismissSheetAfterPull(60f, 700, 3000f, 2f, false))
        assertFalse(dismissSheetAfterPull(60f, 700, -3000f, 2f, false))
    }
    @Test fun cancelledGesturesAlwaysReturnToTheExpandedState() {
        assertFalse(dismissSheetAfterPull(500f, 700, 3000f, 1f, true))
    }
}
