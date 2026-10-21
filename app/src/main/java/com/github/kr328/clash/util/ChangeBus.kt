package com.github.kr328.clash.util

import com.github.kr328.clash.common.log.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process notification that a piece of user-visible state changed, for the parts of the app
 * that must follow changes made elsewhere: the companion gateway turns these into live events for
 * paired controllers (PROTOCOL.md §9.2), and the Home dashboard adopts a mode or node a remote
 * controller picked instead of reverting it.
 *
 * Fed by the shared state-changing paths ([TunnelModeApplier], [applyUserProxySelection], the
 * gateway's own buttons) and, while the gateway runs, by the service's self broadcasts. [remote]
 * tells listeners whether a paired controller caused the change, so the dashboard does not react
 * to its own taps.
 */
object ChangeBus {
    enum class Change {
        Proxies,
        Mode,
        Profiles,
        Providers,
        Connections,
        Settings,
        Power,
        Name,
    }

    fun interface Listener {
        fun onChanged(change: Change, remote: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun emit(change: Change, remote: Boolean = false) {
        for (listener in listeners) {
            try {
                listener.onChanged(change, remote)
            } catch (e: Exception) {
                Log.w("ChangeBus listener failed: ${e.message}")
            }
        }
    }
}
