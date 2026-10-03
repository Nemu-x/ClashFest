package com.github.kr328.clash.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestHistoryTrackingSessionTest {
    @Test
    fun repeatedStartDoesNotAcquireAnotherConsumerAndStopReleasesIt() = runBlocking {
        var acquired = 0
        var released = 0
        val sampled = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = RequestHistoryTrackingSession(
            scope, { acquired++ }, { released++ }, { sampled.complete(Unit) }, { throw it },
            refreshIntervalMs = 60_000L,
        )
        try {
            session.setVisible(true)
            withTimeout(1000L) { sampled.await() }
            session.setVisible(true)
            assertEquals(1, acquired)
            session.setVisible(false)
            session.setVisible(false)
            assertEquals(1, released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun returningToTheSameScreenAcquiresFreshTrackingInsteadOfOnlyReadingStaleSnapshots() = runBlocking {
        var acquired = 0
        var released = 0
        var samples = 0
        var sampled = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = RequestHistoryTrackingSession(
            scope, { acquired++ }, { released++ }, { samples++; sampled.complete(Unit) }, { throw it },
            refreshIntervalMs = 60_000L,
        )
        try {
            session.setVisible(true)
            withTimeout(1000L) { sampled.await() }
            session.setVisible(false)
            assertEquals(acquired, released)
            sampled = CompletableDeferred()
            session.setVisible(true)
            withTimeout(1000L) { sampled.await() }
            assertEquals(2, acquired)
            assertEquals(2, samples)
            session.setVisible(false)
            assertEquals(2, released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unavailableStopAfterUnbindCannotHoldUpTheNextVisibleSession() = runBlocking {
        var acquired = 0
        var samples = 0
        var sampled = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = RequestHistoryTrackingSession(
            scope, { acquired++ }, { awaitCancellation() },
            { samples++; sampled.complete(Unit) }, { throw it },
            refreshIntervalMs = 60_000L, stopTimeoutMs = 10L,
        )
        try {
            session.setVisible(true)
            withTimeout(1000L) { sampled.await() }
            withTimeout(1000L) { session.setVisible(false) }
            sampled = CompletableDeferred()
            session.setVisible(true)
            withTimeout(1000L) { sampled.await() }
            assertEquals(2, acquired)
            assertEquals(2, samples)
            withTimeout(1000L) { session.setVisible(false) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingAnInflightSnapshotReleasesOwnershipWithoutReportingCancellationAsFailure() = runBlocking {
        var released = 0
        var failures = 0
        val querying = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = RequestHistoryTrackingSession(
            scope, {}, { released++ }, { querying.complete(Unit); awaitCancellation() }, { failures++ },
        )
        try {
            session.setVisible(true)
            withTimeout(1000L) { querying.await() }
            withTimeout(1000L) { session.setVisible(false) }
            assertEquals(1, released)
            assertEquals(0, failures)
            assertTrue(querying.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedStartNeverReleasesAnotherConsumersTracking() = runBlocking {
        var released = 0
        var samples = 0
        val failed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = RequestHistoryTrackingSession(
            scope, { throw IllegalStateException("Service unavailable") }, { released++ }, { samples++ },
            { failed.complete(Unit) },
        )
        try {
            session.setVisible(true)
            withTimeout(1000L) { failed.await() }
            session.setVisible(false)
            assertEquals(0, released)
            assertEquals(0, samples)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun destructionCancelsSamplingAndStillReleasesTracking() = runBlocking {
        val sampled = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = RequestHistoryTrackingSession(
            scope, {}, { released.complete(Unit) }, { sampled.complete(Unit) }, { throw it },
            refreshIntervalMs = 60_000L,
        )
        session.setVisible(true)
        withTimeout(1000L) { sampled.await() }
        scope.cancel()
        withTimeout(1000L) { released.await() }
        session.setVisible(false)
    }
}
