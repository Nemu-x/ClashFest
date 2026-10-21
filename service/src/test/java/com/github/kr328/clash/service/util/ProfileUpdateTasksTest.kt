package com.github.kr328.clash.service.util

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProfileUpdateTasksTest {
    private val uuid = UUID(0, 1)

    @Test fun repeatedCallWaitsForTheSameDownload() = runBlocking {
        val tasks = ProfileUpdateTasks()
        val release = CompletableDeferred<Unit>()
        var downloads = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { downloads++; release.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { downloads++ } }
        assertFalse(second.isCompleted, "A repeated call must not report success before the download finishes")
        release.complete(Unit)
        withTimeout(1000) { first.await(); second.await() }
        assertEquals(1, downloads)
    }

    @Test fun allCallersReceiveTheRealFailureAndCanRetry() = runBlocking {
        supervisorScope {
            val tasks = ProfileUpdateTasks()
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { release.await(); throw IOException("offline") } }
            val second = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { error("must join") } }
            release.complete(Unit)
            assertFailsWith<IOException> { first.await() }
            assertFailsWith<IOException> { second.await() }
            var retried = false
            tasks.run(uuid) { retried = true }
            assertTrue(retried)
        }
    }

    @Test fun cancellingAWaitingCallerDoesNotCancelTheDownload() = runBlocking {
        val tasks = ProfileUpdateTasks()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { release.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { error("must join") } }
        second.cancelAndJoin()
        assertFalse(first.isCancelled)
        release.complete(Unit)
        withTimeout(1000) { first.await() }
    }

    @Test fun independentProfilesCanUpdateConcurrently() = runBlocking {
        val tasks = ProfileUpdateTasks()
        val release = CompletableDeferred<Unit>()
        var downloads = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { downloads++; release.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(UUID(0, 2)) { downloads++; release.await() } }
        assertEquals(2, downloads)
        release.complete(Unit)
        withTimeout(1000) { first.await(); second.await() }
    }

    @Test fun ownerCancellationReleasesWaitersAndAllowsRetry() = runBlocking {
        val tasks = ProfileUpdateTasks()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { release.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { tasks.run(uuid) { error("must join") } }
        first.cancelAndJoin()
        withTimeout(1000) { second.join() }
        assertTrue(second.isCancelled)
        var retried = false
        tasks.run(uuid) { retried = true }
        assertTrue(retried)
    }
}
