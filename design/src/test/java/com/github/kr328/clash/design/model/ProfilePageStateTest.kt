package com.github.kr328.clash.design.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePageStateTest {
    private val state = ProfilePageState()
    private val first = UUID(0, 1)
    private val second = UUID(0, 2)

    @Test
    fun repeatedUpdateIsRejectedUntilCompletion() {
        assertTrue(state.beginUpdate(first))
        assertFalse(state.beginUpdate(first))
        state.finishUpdate(first, true)
        assertFalse(state.isUpdating(first))
        assertTrue(state.beginUpdate(first))
    }

    @Test
    fun failureIsShownAndClearedWhenRetryStarts() {
        state.beginUpdate(first)
        state.finishUpdate(first, false)
        assertTrue(state.hasUpdateError(first))
        assertFalse(state.isUpdating(first))
        assertTrue(state.beginUpdate(first))
        assertFalse(state.hasUpdateError(first))
        assertTrue(state.isUpdating(first))
    }

    @Test
    fun subscriptionsHaveIndependentUpdateStates() {
        state.beginUpdate(first)
        state.beginUpdate(second)
        state.finishUpdate(first, false)
        assertTrue(state.hasUpdateError(first))
        assertTrue(state.isUpdating(second))
        assertFalse(state.hasUpdateError(second))
    }

    @Test
    fun successfulBackgroundUpdateClearsOldError() {
        state.finishUpdate(first, false)
        state.finishUpdate(first, true)
        assertFalse(state.hasUpdateError(first))
    }

    @Test
    fun observedFailureDoesNotUnlockAnUpdateWhileItRetries() {
        state.beginUpdate(first)
        state.recordObservedUpdate(first, false)
        assertTrue(state.isUpdating(first))
        assertFalse(state.hasUpdateError(first))
        assertFalse(state.beginUpdate(first))
        state.finishUpdate(first, false)
        assertTrue(state.hasUpdateError(first))
    }

    @Test
    fun backgroundUpdateResultIsVisibleWithoutUserInitiatedUpdate() {
        state.recordObservedUpdate(first, false)
        assertTrue(state.hasUpdateError(first))
        state.recordObservedUpdate(first, true)
        assertFalse(state.hasUpdateError(first))
    }

    @Test
    fun removedSubscriptionsDoNotKeepStaleStates() {
        state.beginUpdate(first)
        state.finishUpdate(second, false)
        state.retainProfiles(emptySet())
        assertFalse(state.isUpdating(first))
        assertFalse(state.hasUpdateError(second))
    }
}
