package com.github.kr328.clash.service.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/** Repeated callers await the same download and receive its actual result. */
internal class ProfileUpdateTasks {
    private val active = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()

    suspend fun run(uuid: UUID, update: suspend () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        val existing = active.putIfAbsent(uuid, completion)
        if (existing != null) {
            existing.await()
            return
        }
        try {
            update()
            completion.complete(Unit)
        } catch (error: Throwable) {
            completion.completeExceptionally(error)
            throw error
        } finally {
            active.remove(uuid, completion)
        }
    }
}
