package com.github.kr328.clash.companion.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.util.ChangeBus

/**
 * While the gateway runs, turns the service's self broadcasts into [ChangeBus] changes, so the
 * event stream (PROTOCOL.md §9.2) also reports what happens on the device itself or in the
 * `:background` process: VPN on/off, profile switch / load / update, a selector patched from any
 * screen, the mode override, connection lists. Emitted as local (`remote = false`) changes.
 */
internal class CompanionChangeBridge(private val context: Context) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName) return
            val changes = when (intent?.action) {
                Intents.ACTION_CLASH_STARTED, Intents.ACTION_CLASH_STOPPED, Intents.ACTION_SERVICE_RECREATED ->
                    listOf(ChangeBus.Change.Power, ChangeBus.Change.Proxies, ChangeBus.Change.Providers, ChangeBus.Change.Connections)
                Intents.ACTION_PROFILE_CHANGED, Intents.ACTION_PROFILE_LOADED ->
                    listOf(ChangeBus.Change.Profiles, ChangeBus.Change.Proxies, ChangeBus.Change.Providers)
                Intents.ACTION_PROFILE_UPDATE_COMPLETED ->
                    listOf(ChangeBus.Change.Profiles, ChangeBus.Change.Providers)
                Intents.ACTION_PROXY_SELECTION_CHANGED -> listOf(ChangeBus.Change.Proxies)
                Intents.ACTION_OVERRIDE_CHANGED -> listOf(ChangeBus.Change.Mode)
                Intents.ACTION_CONNECTIONS_CHANGED -> listOf(ChangeBus.Change.Connections)
                else -> emptyList()
            }
            changes.forEach { ChangeBus.emit(it) }
        }
    }

    fun register() {
        if (registered) return
        try {
            context.registerReceiverCompat(
                receiver,
                IntentFilter().apply {
                    addAction(Intents.ACTION_CLASH_STARTED)
                    addAction(Intents.ACTION_CLASH_STOPPED)
                    addAction(Intents.ACTION_SERVICE_RECREATED)
                    addAction(Intents.ACTION_PROFILE_CHANGED)
                    addAction(Intents.ACTION_PROFILE_LOADED)
                    addAction(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
                    addAction(Intents.ACTION_PROXY_SELECTION_CHANGED)
                    addAction(Intents.ACTION_OVERRIDE_CHANGED)
                    addAction(Intents.ACTION_CONNECTIONS_CHANGED)
                },
                Permissions.RECEIVE_SELF_BROADCASTS,
                null,
            )
            registered = true
        } catch (e: Exception) {
            Log.w("Companion: change bridge register failed: ${e.message}")
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w("Companion: change bridge unregister failed: ${e.message}")
        } finally {
            registered = false
        }
    }
}
