package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.RequestHistoryRepository
import com.github.kr328.clash.service.model.RequestHistorySnapshot
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.remote.IProxyDelayObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ProxyHardener
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryTrafficNow(): Long {
        return Clash.queryTrafficNow()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryAllProxyGroupNamesIncludingHidden(): List<String> {
        return Clash.queryAllGroupNamesIncludingHidden()
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryConnectionsSnapshot(): String {
        return Clash.queryConnectionsSnapshot()
    }

    override fun queryRequestHistory(): String {
        return json.encodeToString<RequestHistorySnapshot>(RequestHistoryRepository.snapshot())
    }

    override fun clearRequestHistory() {
        RequestHistoryRepository.clear()
    }

    override fun startRequestHistoryTracking() {
        RequestHistoryRepository.startTracking()
    }

    override fun stopRequestHistoryTracking() {
        RequestHistoryRepository.stopTracking()
    }

    override fun closeConnection(id: String): Boolean {
        return Clash.closeConnection(id)
    }

    override fun closeAllConnections(): Int {
        return Clash.closeAllConnections()
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    /**
     * Persist the selection and, in **Global** mode only, point [GLOBAL] at the group the user
     * picked in: there all traffic uses the GLOBAL adapter, so a leaf change inside another group
     * has no effect until GLOBAL's active child is that group. mihomo builds GLOBAL from the full
     * proxy list (every node and every group), so a single hop is always enough.
     *
     * Never rewrite any other selector. In Rule mode the other groups are routing policy (a
     * "CN direct" group whose value must stay DIRECT, a "final" group, ...); the former ancestor
     * walk force-pointed them at the group the user tapped in, persisted that, and made GeoIP /
     * geosite rules look broken (#205).
     */
    override fun patchSelector(group: String, name: String): Boolean {
        val ok = Clash.patchSelector(group, name)
        val current = store.activeProfile
        if (!ok) {
            current?.let { SelectionDao().removeSelected(it, group) }

            return false
        }

        current?.let { SelectionDao().setSelected(Selection(it, group, name)) }
        syncGlobalSelector(current, group)

        return true
    }

    private fun syncGlobalSelector(current: java.util.UUID?, selectedGroup: String) {
        if (selectedGroup.isBlank() || selectedGroup == "GLOBAL") return

        val state = runCatching { Clash.queryTunnelState() }.getOrNull()
        if (state?.mode != TunnelState.Mode.Global) return

        val global = runCatching { Clash.queryGroup("GLOBAL", ProxySort.Default) }.getOrNull()
            ?: return
        if (global.proxies.none { it.name == selectedGroup } || global.now == selectedGroup) return
        if (Clash.patchSelector("GLOBAL", selectedGroup)) {
            current?.let { SelectionDao().setSelected(Selection(it, "GLOBAL", selectedGroup)) }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        if (slot == Clash.OverrideSlot.Session) {
            ProxyHardener.applyTo(
                configuration = configuration,
                mode = store.proxyHardeningMode,
                seedGeoMirrors = store.seedDefaultGeoMirrors,
            )
        }
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun healthCheckPerProxy(group: String, testUrl: String, observer: IProxyDelayObserver) {
        // JNI fires onProxyDelay from arbitrary worker threads. AIDL stubs are
        // not thread-safe across simultaneous calls, so the observer must
        // tolerate concurrent onDelay() — IPC marshals them serially through
        // the binder transaction queue, but a DeadObjectException from a
        // crashed activity must not kill the whole health-check pipeline.
        val finalErr: String? = runCatching {
            Clash.healthCheckPerProxy(group, testUrl) { name, ms, err ->
                runCatching { observer.onDelay(group, name, ms, err) }
            }.await()
            null
        }.getOrElse { e ->
            e.message ?: e::class.simpleName ?: "unknown"
        }
        runCatching { observer.onComplete(finalErr) }
    }

    override fun healthCheckAll() {
        Clash.healthCheckAll()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override suspend fun updateGeoDatabases(): String? =
        withContext(Dispatchers.IO) { Clash.updateGeoDatabases() }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}
