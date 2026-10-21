package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.StatusClient
import kotlinx.coroutines.delay

/**
 * The non-UI half of switching the tunnel mode (Rule / Global), shared by the Home mode toggle and
 * the companion gateway so both persist and apply a change the same way: normalize, persist
 * [UiStore.tunnelModePreference], patch the Session override, keep GLOBAL off DIRECT/REJECT, then
 * verify and re-apply once. The dashboard keeps only its UI bits (segment state, pending mode,
 * toasts).
 *
 * The app offers Rule and Global only; Direct is folded into Rule everywhere ([normalize]).
 */
object TunnelModeApplier {
    fun normalize(mode: TunnelState.Mode): TunnelState.Mode =
        if (mode == TunnelState.Mode.Direct) TunnelState.Mode.Rule else mode

    fun parse(name: String): TunnelState.Mode? =
        when (name) {
            TunnelState.Mode.Rule.name -> TunnelState.Mode.Rule
            TunnelState.Mode.Global.name -> TunnelState.Mode.Global
            TunnelState.Mode.Direct.name -> TunnelState.Mode.Rule
            else -> null
        }

    /**
     * Persist [mode] and, when the tunnel is [running] (null = ask the service), apply it to the
     * engine. Announces [ChangeBus.Change.Mode] afterwards, also when it throws.
     *
     * @param remote true when a paired controller asked for it (see [ChangeBus]).
     * @return true when the engine confirmed the mode, false when it is not running or still
     *         converging after the re-apply.
     */
    suspend fun apply(context: Context, mode: TunnelState.Mode, running: Boolean?, remote: Boolean): Boolean {
        val normalized = normalize(mode)
        UiStore(context).tunnelModePreference = normalized.name
        try {
            val isRunning = running ?: StatusClient(context).statusSnapshot().serviceRunning
            if (!isRunning) return false

            patchSessionMode(normalized)
            if (normalized == TunnelState.Mode.Global) {
                ensureGlobalSelectionSafe()
            }
            delay(120L)
            val applied = runCatching {
                withClash { queryTunnelState().mode }
            }.getOrNull()
            if (applied != null && normalize(applied) != normalized) {
                patchSessionMode(normalized)
                return false
            }
            return applied != null
        } finally {
            ChangeBus.emit(ChangeBus.Change.Mode, remote)
        }
    }

    /** The mode the app currently shows: the engine's when running, else the saved preference. */
    suspend fun current(context: Context, running: Boolean): TunnelState.Mode {
        val preferred = parse(UiStore(context).tunnelModePreference)
        if (!running) return preferred ?: TunnelState.Mode.Rule
        return preferred ?: normalize(withClash { queryTunnelState().mode })
    }

    private suspend fun patchSessionMode(mode: TunnelState.Mode) {
        withClash {
            val o = queryOverride(Clash.OverrideSlot.Session)
            o.mode = mode
            patchOverride(Clash.OverrideSlot.Session, o)
        }
    }

    private fun isBlockedGlobalChoice(name: String): Boolean =
        name.equals("DIRECT", ignoreCase = true) || name.equals("REJECT", ignoreCase = true)

    private fun isBlockedGlobalChoice(proxy: Proxy): Boolean =
        proxy.type == Proxy.Type.Direct ||
            proxy.type == Proxy.Type.Reject ||
            isBlockedGlobalChoice(proxy.name)

    /** In Global mode never leave GLOBAL on DIRECT/REJECT: point it at the first usable member. */
    suspend fun ensureGlobalSelectionSafe() {
        runCatching {
            val global = withClash {
                queryProxyGroup("GLOBAL", ProxySort.Default)
            }
            if (!isBlockedGlobalChoice(global.now)) return@runCatching
            val firstUsable = global.proxies.firstOrNull { !isBlockedGlobalChoice(it) }?.name ?: return@runCatching
            withClash { patchSelector("GLOBAL", firstUsable) }
        }
    }

    suspend fun ensureGlobalSelectionSafeWithRetry(
        context: Context,
        attempts: Int = 10,
        delayMs: Long = 180L,
    ) {
        repeat(attempts) { index ->
            val done = runCatching {
                val running = StatusClient(context).statusSnapshot().serviceRunning
                if (!running) return@runCatching true

                val mode = withClash { queryTunnelState().mode }
                if (normalize(mode) != TunnelState.Mode.Global) return@runCatching true

                val hasGlobal = withClash { queryProxyGroupNames(false).any { it.equals("GLOBAL", ignoreCase = true) } }
                if (!hasGlobal) return@runCatching false

                ensureGlobalSelectionSafe()
                true
            }.getOrDefault(false)

            if (done) return
            if (index < attempts - 1) delay(delayMs)
        }
    }
}
