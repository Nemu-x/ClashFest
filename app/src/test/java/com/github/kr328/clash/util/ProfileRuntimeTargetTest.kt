package com.github.kr328.clash.util

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRuntimeTargetTest {
    private val active = UUID(0, 1)
    private val other = UUID(0, 2)

    @Test
    fun activeSubscriptionCanUseRunningEngine() {
        assertTrue(ProfileRuntimeTarget.canUseEngine(true, active, active))
    }

    @Test
    fun otherSubscriptionCannotChangeRunningEngine() {
        assertFalse(ProfileRuntimeTarget.canUseEngine(true, active, other))
    }

    @Test
    fun stoppedEngineUsesSavedSelectionsEvenForActiveSubscription() {
        assertFalse(ProfileRuntimeTarget.canUseEngine(false, active, active))
    }

    @Test
    fun missingActiveSubscriptionCannotTargetEngine() {
        assertFalse(ProfileRuntimeTarget.canUseEngine(true, null, other))
    }
}
