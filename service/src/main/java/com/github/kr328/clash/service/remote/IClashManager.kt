package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun queryTrafficTotal(): Long
    fun queryTrafficNow(): Long
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryAllProxyGroupNamesIncludingHidden(): List<String>
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup
    fun queryConfiguration(): UiConfiguration
    fun queryProviders(): ProviderList

    fun queryConnectionsSnapshot(): String
    fun closeConnection(id: String): Boolean
    fun closeAllConnections(): Int
    fun queryRequestHistory(): String
    fun clearRequestHistory()
    fun startRequestHistoryTracking()
    fun stopRequestHistoryTracking()

    fun patchSelector(group: String, name: String): Boolean

    suspend fun healthCheck(group: String)

    /**
     * @param testUrl one-shot latency target the user typed, or blank to use each provider's
     *        configured health-check URL. Never persisted; the automatic url-test timers are
     *        unaffected.
     */
    suspend fun healthCheckPerProxy(group: String, testUrl: String, observer: IProxyDelayObserver)

    fun healthCheckAll()
    suspend fun updateProvider(type: Provider.Type, name: String)

    /** Download fresh GeoIP/GeoSite databases. Returns null on success, or the engine error. */
    suspend fun updateGeoDatabases(): String?

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun setLogObserver(observer: ILogObserver?)

    /**
     * Measure a single proxy (tap on its latency capsule). Same observer contract as
     * [healthCheckPerProxy]: one onDelay, then onComplete. Declared last on purpose: kaidl
     * numbers binder transactions by declaration order, so appending keeps existing codes stable.
     */
    suspend fun healthCheckProxy(group: String, proxy: String, testUrl: String, observer: IProxyDelayObserver)
}
