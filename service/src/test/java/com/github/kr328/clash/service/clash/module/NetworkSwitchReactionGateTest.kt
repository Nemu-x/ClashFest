package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSwitchReactionGateTest {
    /**
     * The flap-window tests below pin the guard explicitly so they exercise the mechanism
     * rather than whatever the shipped default happens to be; [defaultFlapGuardSpacesReactions]
     * is the one that pins the default itself.
     */
    private val testFlapGuardMs = 3_000L

    @Test
    fun initialObservationAndStartupChangesDoNotReact() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 1_000L)

        assertNull(gate.observe("cellular", now = 1_000L, enabled = true).reaction)
        val startupSwitch = gate.observe("wifi", now = 5_999L, enabled = true)

        assertNull(startupSwitch.reaction)
        assertTrue(startupSwitch.cancelPendingRetry)
    }

    @Test
    fun switchAfterStartupGraceReactsImmediately() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L)
        gate.observe("wifi", now = 0L, enabled = true)

        val decision = gate.observe("cellular", now = 5_000L, enabled = true)

        assertEquals("cellular", decision.reaction)
        assertNull(decision.retryAfterMs)
    }

    @Test
    fun switchInsideFlapWindowGetsGuaranteedRetry() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L, flapGuardMs = testFlapGuardMs)
        gate.observe("wifi", now = 0L, enabled = true)
        gate.observe("cellular", now = 5_000L, enabled = true)

        val deferred = gate.observe("wifi", now = 7_000L, enabled = true)
        assertNull(deferred.reaction)
        assertEquals(1_000L, deferred.retryAfterMs)

        val retried = gate.retry("wifi", now = 8_000L, enabled = true)
        assertEquals("wifi", retried.reaction)
        assertNull(retried.retryAfterMs)
    }

    @Test
    fun newerCandidateReplacesDeferredCandidate() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L, flapGuardMs = testFlapGuardMs)
        gate.observe("wifi-a", now = 0L, enabled = true)
        gate.observe("cellular", now = 5_000L, enabled = true)
        gate.observe("wifi-a", now = 7_000L, enabled = true)

        val replacement = gate.observe("wifi-b", now = 7_500L, enabled = true)

        assertEquals(500L, replacement.retryAfterMs)
        assertTrue(replacement.cancelPendingRetry)
        assertEquals("wifi-b", gate.retry("wifi-b", now = 8_000L, enabled = true).reaction)
    }

    @Test
    fun returningToSettledNetworkCancelsDeferredReaction() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L, flapGuardMs = testFlapGuardMs)
        gate.observe("wifi", now = 0L, enabled = true)
        gate.observe("cellular", now = 5_000L, enabled = true)

        val deferred = gate.observe("wifi", now = 7_000L, enabled = true)
        assertEquals(1_000L, deferred.retryAfterMs)

        val recovered = gate.observe("cellular", now = 7_500L, enabled = true)

        assertNull(recovered.reaction)
        assertNull(recovered.retryAfterMs)
        assertTrue(recovered.cancelPendingRetry)
    }

    @Test
    fun airplaneModeCancelsRetryAndRecoveryReacts() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L, flapGuardMs = testFlapGuardMs)
        gate.observe("wifi", now = 0L, enabled = true)
        gate.observe("cellular", now = 5_000L, enabled = true)
        gate.observe("wifi", now = 7_000L, enabled = true)

        val offline = gate.observe(null, now = 7_200L, enabled = true)
        assertTrue(offline.cancelPendingRetry)
        assertNull(offline.reaction)

        val recovered = gate.observe("cellular", now = 8_000L, enabled = true)
        assertEquals("cellular", recovered.reaction)
    }

    /**
     * The shipped guard is 5s: long enough that one network change arriving as a burst of
     * callbacks reacts once, short enough that a genuine Wi-Fi <-> cellular switch is not left
     * waiting. Pinned here because the value is a deliberate trade-off, not an arbitrary number.
     */
    @Test
    fun defaultFlapGuardSpacesReactions() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L)
        gate.observe("wifi", now = 0L, enabled = true)
        gate.observe("cellular", now = 5_000L, enabled = true)

        val tooSoon = gate.observe("wifi", now = 7_000L, enabled = true)
        assertNull(tooSoon.reaction)
        assertEquals(3_000L, tooSoon.retryAfterMs)

        assertEquals("wifi", gate.retry("wifi", now = 10_000L, enabled = true).reaction)
    }

    @Test
    fun disablingFeatureUpdatesStateWithoutLaterSurpriseReaction() {
        val gate = NetworkSwitchReactionGate<String>(startedAt = 0L)
        gate.observe("wifi", now = 0L, enabled = true)

        val disabled = gate.observe("cellular", now = 5_000L, enabled = false)
        assertNull(disabled.reaction)
        assertTrue(disabled.cancelPendingRetry)

        val unchanged = gate.observe("cellular", now = 6_000L, enabled = true)
        assertNull(unchanged.reaction)
        assertNull(unchanged.retryAfterMs)
        assertFalse(unchanged.cancelPendingRetry)
    }
}
