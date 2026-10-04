package com.github.kr328.clash.widget

internal class WidgetToggleGate {
    private var inFlight = false
    private var lastStarted: Long? = null

    @Synchronized
    fun tryBegin(now: Long, restoring: Boolean = false): Boolean {
        if (inFlight || !restoring && lastStarted?.let { now - it < 600L } == true) return false
        inFlight = true
        lastStarted = now
        return true
    }

    @Synchronized
    fun finish() {
        inFlight = false
    }

    companion object {
        val shared = WidgetToggleGate()
    }
}
