package com.github.kr328.clash.main

import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun normalizeTunnelMode(mode: TunnelState.Mode): TunnelState.Mode =
    if (mode == TunnelState.Mode.Direct) TunnelState.Mode.Rule else mode

internal fun parseTunnelMode(name: String): TunnelState.Mode? =
    when (name) {
        TunnelState.Mode.Rule.name -> TunnelState.Mode.Rule
        TunnelState.Mode.Global.name -> TunnelState.Mode.Global
        TunnelState.Mode.Direct.name -> TunnelState.Mode.Rule
        else -> null
    }

/**
 * Rule / Global switching of the Home mode selector, plus the guard that keeps GLOBAL from
 * resting on DIRECT / REJECT. Owns [pendingTunnelMode], the optimistic mode the dashboard
 * shows until the core confirms it.
 */
internal class MainTunnelModeController(private val activity: MainActivity) {
    private val uiStore get() = activity.store
    private val clashRunning get() = activity.isClashRunning

    var pendingTunnelMode: TunnelState.Mode? = null

    suspend fun patchTunnelMode(design: MainDesign, mode: TunnelState.Mode, scheduleRefresh: () -> Unit) {
        design.applyTunnelMode(mode, scheduleRefresh)
    }

    private suspend fun MainDesign.applyTunnelMode(mode: TunnelState.Mode, scheduleRefresh: () -> Unit) {
        try {
            val normalized = normalizeTunnelMode(mode)
            uiStore.tunnelModePreference = normalized.name
            pendingTunnelMode = normalized
            setMode(normalized)

            val running = clashRunning || withContext(Dispatchers.IO) { activity.resolveStatusSnapshot().serviceRunning }
            if (running) {
                val error = runCatching {
                    withClash {
                        val o = queryOverride(Clash.OverrideSlot.Session)
                        o.mode = normalized
                        patchOverride(Clash.OverrideSlot.Session, o)
                    }
                    if (normalized == TunnelState.Mode.Global) {
                        ensureGlobalSelectionSafe()
                    }
                    delay(120L)
                    val applied = runCatching {
                        withClash { queryTunnelState().mode }
                    }.getOrNull()
                    if (applied != null && normalizeTunnelMode(applied) != normalized) {
                        withClash {
                            val o = queryOverride(Clash.OverrideSlot.Session)
                            o.mode = normalized
                            patchOverride(Clash.OverrideSlot.Session, o)
                        }
                    } else if (applied != null) {
                        pendingTunnelMode = null
                    }
                }.exceptionOrNull()

                if (error != null && error !is CancellationException) {
                    pendingTunnelMode = null
                    showExceptionToast(error as? Exception ?: Exception(error))
                }
            }
        } catch (e: Exception) {
            pendingTunnelMode = null
            if (e !is CancellationException) showExceptionToast(e)
        } finally {
            // Defer heavy dashboard work so mode UI updates immediately (Rule / Global switch).
            launch {
                delay(48)
                scheduleRefresh()
            }
        }
    }

    private fun isBlockedGlobalChoice(name: String): Boolean =
        name.equals("DIRECT", ignoreCase = true) || name.equals("REJECT", ignoreCase = true)

    private fun isBlockedGlobalChoice(proxy: Proxy): Boolean =
        proxy.type == Proxy.Type.Direct ||
            proxy.type == Proxy.Type.Reject ||
            isBlockedGlobalChoice(proxy.name)

    suspend fun ensureGlobalSelectionSafe() {
        runCatching {
            val global = withClash {
                queryProxyGroup("GLOBAL", com.github.kr328.clash.core.model.ProxySort.Default)
            }
            if (!isBlockedGlobalChoice(global.now)) return@runCatching
            val firstUsable = global.proxies.firstOrNull { !isBlockedGlobalChoice(it) }?.name ?: return@runCatching
            withClash { patchSelector("GLOBAL", firstUsable) }
        }
    }

    suspend fun ensureGlobalSelectionSafeWithRetry(
        attempts: Int = 10,
        delayMs: Long = 180L,
    ) {
        repeat(attempts) { index ->
            val done = runCatching {
                val running = activity.resolveStatusSnapshot().serviceRunning
                if (!running) return@runCatching true

                val mode = withClash { queryTunnelState().mode }
                if (normalizeTunnelMode(mode) != TunnelState.Mode.Global) return@runCatching true

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
