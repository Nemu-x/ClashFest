package com.github.kr328.clash.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceTest {
    @Test
    fun resumedCallerCanCancelAnotherWaiterDuringReconnect() = runBlocking {
        val resource = Resource<String>()
        lateinit var other: Deferred<String>
        val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            val value = resource.get()
            other.cancel()
            value
        }
        other = async(start = CoroutineStart.UNDISPATCHED) { resource.get() }
        try {
            resource.set("service")
            assertEquals("service", withTimeout(1_000L) { first.await() })
            assertTrue(other.isCancelled)
        } finally {
            first.cancel()
            other.cancel()
        }
    }

    @Test
    fun pendingRequestSurvivesDisconnectAndReceivesReconnectedService() = runBlocking {
        val resource = Resource<String>()
        val request = async(start = CoroutineStart.UNDISPATCHED) { resource.get() }
        try {
            resource.set(null)
            resource.set(null)
            assertFalse(request.isCompleted)

            resource.set("reconnected")
            assertEquals("reconnected", withTimeout(1_000L) { request.await() })
        } finally {
            request.cancel()
        }
    }

    @Test
    fun cancellingOneCallerDoesNotCancelOtherPendingRequests() = runBlocking {
        val resource = Resource<String>()
        val destroyedScreen = async(start = CoroutineStart.UNDISPATCHED) { resource.get() }
        val activeScreen = async(start = CoroutineStart.UNDISPATCHED) { resource.get() }
        try {
            resource.set(null)
            destroyedScreen.cancel()
            destroyedScreen.join()
            assertTrue(destroyedScreen.isCancelled)
            assertFalse(activeScreen.isCompleted)

            resource.set("service")
            assertEquals("service", withTimeout(1_000L) { activeScreen.await() })
        } finally {
            destroyedScreen.cancel()
            activeScreen.cancel()
        }
    }

    @Test
    fun staleResetDoesNotDiscardReconnectedService() = runBlocking {
        val resource = Resource<Any>()
        val staleService = Any()
        val currentService = Any()
        resource.set(staleService)
        resource.set(currentService)
        resource.reset(staleService)

        assertEquals(currentService, withTimeout(1_000L) { resource.get() })
        resource.reset(currentService)
        val request = async(start = CoroutineStart.UNDISPATCHED) { resource.get() }
        try {
            assertFalse(request.isCompleted)
            resource.set(staleService)
            assertEquals(staleService, withTimeout(1_000L) { request.await() })
        } finally {
            request.cancel()
        }
    }
}
