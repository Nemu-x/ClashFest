package com.github.kr328.clash.remote

import com.github.kr328.clash.common.util.RemoteServiceUnavailableException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Resource<T> {
    private interface Callback<T> {
        val isActive: Boolean
        fun accept(value: T)
        fun fail(error: Exception)
    }

    private val pending: MutableSet<Callback<T>> = mutableSetOf()

    private var value: T? = null
    private var failure: Exception? = null

    val isReady: Boolean
        @Synchronized get() = value != null

    suspend fun get(timeoutMillis: Long = 10_000L): T =
        withTimeoutOrNull(timeoutMillis) { await() } ?: throw RemoteServiceUnavailableException()

    private suspend fun await(): T {
        return suspendCancellableCoroutine { ctx ->
            val callback = object : Callback<T> {
                override val isActive: Boolean
                    get() = ctx.isActive

                override fun accept(value: T) {
                    ctx.resume(value)
                }

                override fun fail(error: Exception) {
                    ctx.resumeWithException(error)
                }
            }

            ctx.invokeOnCancellation {
                cancel(callback)
            }

            get(callback)
        }
    }

    fun set(v: T?) {
        setAndNotify(v)
    }

    fun reset(v: T) {
        resetIfMatched(v)
    }

    @Synchronized
    fun fail(error: Exception) {
        value = null
        failure = error
        val callbacks = pending.toList()
        pending.clear()
        callbacks.forEach { it.fail(error) }
    }

    @Synchronized
    private fun get(callback: Callback<T>) {
        val v = value
        val error = failure

        if (error != null) {
            callback.fail(error)
        } else if (v == null) {
            if (callback.isActive) pending.add(callback)
        } else {
            callback.accept(v)
        }
    }

    @Synchronized
    private fun setAndNotify(value: T?) {
        this.value = value
        failure = null

        // A disconnected service can reconnect while the caller's screen is still alive.
        // The caller's own coroutine cancellation removes its waiter on screen teardown.
        if (value == null) return

        val callbacks = pending.toList()
        pending.clear()
        callbacks.forEach {
            it.accept(value)
        }
    }

    @Synchronized
    private fun resetIfMatched(value: T) {
        if (this.value === value) {
            this.value = null
        }
    }

    @Synchronized
    private fun cancel(callback: Callback<T>) {
        pending.remove(callback)
    }
}
