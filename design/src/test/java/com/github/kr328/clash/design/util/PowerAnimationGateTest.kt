package com.github.kr328.clash.design.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerAnimationGateTest {
    @Test fun visibleHomeAnimatesWithoutDependingOnTunnelState() {
        assertTrue(shouldAnimatePowerAmbient(true, true, true, false, true))
    }
    @Test fun offscreenAndBackgroundPagesStopTheirAmbientWork() {
        assertFalse(shouldAnimatePowerAmbient(true, true, false, false, true))
        assertFalse(shouldAnimatePowerAmbient(true, false, true, false, true))
        assertFalse(shouldAnimatePowerAmbient(false, true, true, false, true))
    }
    @Test fun disabledSystemMotionAndCoveringSheetsRemainRespected() {
        assertFalse(shouldAnimatePowerAmbient(true, true, true, false, false))
        assertFalse(shouldAnimatePowerAmbient(true, true, true, true, true))
    }
}
