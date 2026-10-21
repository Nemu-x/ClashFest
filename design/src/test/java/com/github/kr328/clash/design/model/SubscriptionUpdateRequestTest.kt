package com.github.kr328.clash.design.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionUpdateRequestTest {
    private val uuid = UUID(0, 1)

    @Test fun unsupportedSourcesNeverDispatchOrLockTheButton() {
        val state = ProfilePageState()
        var sends = 0
        assertEquals(SubscriptionUpdateRequestResult.Unsupported,
            beginSubscriptionUpdateRequest(state, uuid, false) { sends++; true })
        assertEquals(0, sends)
        assertFalse(state.isUpdating(uuid))
    }

    @Test fun everyEntryPointSharesOneInFlightRequest() {
        val state = ProfilePageState()
        var sends = 0
        val dispatch = { sends++; true }
        assertEquals(SubscriptionUpdateRequestResult.Started, beginSubscriptionUpdateRequest(state, uuid, true, dispatch))
        assertEquals(SubscriptionUpdateRequestResult.AlreadyRunning, beginSubscriptionUpdateRequest(state, uuid, true, dispatch))
        assertEquals(1, sends)
    }

    @Test fun rejectedDispatchUnlocksRetryAndReportsFailure() {
        val state = ProfilePageState()
        assertEquals(SubscriptionUpdateRequestResult.Unavailable, beginSubscriptionUpdateRequest(state, uuid, true) { false })
        assertFalse(state.isUpdating(uuid))
        assertTrue(state.hasUpdateError(uuid))
        assertEquals(SubscriptionUpdateRequestResult.Started, beginSubscriptionUpdateRequest(state, uuid, true) { true })
    }

    @Test fun observerFeedbackResumesAfterManualCompletionWindow() {
        var time = 100L
        val state = ProfilePageState { time }
        state.beginUpdate(uuid)
        assertFalse(state.recordObservedUpdate(uuid, false))
        state.finishUpdate(uuid, true)
        assertFalse(state.recordObservedUpdate(uuid, false))
        assertTrue(state.wasUpdatedSuccessfully(uuid))
        time += 3001L
        assertTrue(state.recordObservedUpdate(uuid, false))
        assertTrue(state.hasUpdateError(uuid))
    }

    @Test fun cancelledUpdateIsNotReportedAsARequestFailure() {
        val state = ProfilePageState()
        state.beginUpdate(uuid)
        state.cancelUpdate(uuid)
        assertFalse(state.isUpdating(uuid))
        assertFalse(state.hasUpdateError(uuid))
    }
}
