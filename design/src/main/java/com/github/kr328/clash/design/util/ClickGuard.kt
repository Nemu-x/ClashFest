package com.github.kr328.clash.design.util

/** UI-thread guard: each action has its own cooldown; dialogs stay blocked until dismissal. */
internal class ClickGuard<K>(
    private val intervalMillis: Long,
    private val nowMillis: () -> Long,
) {
    private val lastAccepted = mutableMapOf<K, Long>()
    private val openActions = mutableSetOf<K>()

    fun accept(action: K): Boolean {
        if (isOpen(action)) return false
        val now = nowMillis()
        val last = lastAccepted[action]
        if (last != null && now - last < intervalMillis) return false
        lastAccepted[action] = now
        return true
    }

    fun isOpen(action: K): Boolean = action in openActions

    fun tryOpen(action: K): Boolean = openActions.add(action)

    fun close(action: K) {
        openActions.remove(action)
    }
}
