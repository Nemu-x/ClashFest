package com.github.kr328.clash.design.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerAnimationGateTest {
    @Test
    fun connectedHomeStopsWhileOtherTabsRemainAttached() {
        assertTrue(shouldAnimatePowerAmbient(true, true, true, true, false, true))
        assertFalse(shouldAnimatePowerAmbient(true, true, true, false, false, true))
        assertTrue(shouldAnimatePowerAmbient(true, true, true, true, false, true))
    }

    @Test
    fun backgroundAndDetachedDesignCannotKeepAnimating() {
        assertFalse(shouldAnimatePowerAmbient(true, true, false, true, false, true))
        assertFalse(shouldAnimatePowerAmbient(true, false, true, true, false, true))
        assertTrue(shouldAnimatePowerAmbient(true, true, true, true, false, true))
    }

    @Test
    fun aboutAndDisabledSystemAnimationsKeepConnectedHomeStatic() {
        assertFalse(shouldAnimatePowerAmbient(true, true, true, true, true, true))
        assertFalse(shouldAnimatePowerAmbient(true, true, true, true, false, false))
    }

    @Test
    fun disconnectedHomeNeverStartsAmbientAnimation() {
        assertFalse(shouldAnimatePowerAmbient(false, true, true, true, false, true))
    }
}
