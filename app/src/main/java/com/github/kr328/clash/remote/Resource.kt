package com.github.kr328.clash.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Resource<T> {
    private interface Callback<T> {
        val isActive: Boolean
        fun accept(value: T)
    }

    private val pending: MutableSet<Callback<T>> = mutableSetOf()

    private var value: T? = null

    suspend fun get(): T {
        return suspendCancellableCoroutine { ctx ->
            val callback = object : Callback<T> {
                override val isActive: Boolean
                    get() = ctx.isActive

                override fun accept(value: T) {
                    ctx.resume(value)
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
    private fun get(callback: Callback<T>) {
        val v = value

        if (v == null) {
            if (callback.isActive) pending.add(callback)
        } else {
            callback.accept(v)
        }
    }

    @Synchronized
    private fun setAndNotify(value: T?) {
        this.value = value

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
