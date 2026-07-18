package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.model.LocalProxyInfo
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

    /**
     * Address + credentials of the opt-in local proxy listener, so Settings can show the user what
     * to paste into a container or another app. Credentials live in the service process; this is
     * the only way the UI process can read them.
     */
    fun queryLocalProxyInfo(): LocalProxyInfo
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

    suspend fun healthCheckPerProxy(group: String, observer: IProxyDelayObserver)

    fun healthCheckAll()
    suspend fun updateProvider(type: Provider.Type, name: String)

    /** Download fresh GeoIP/GeoSite databases. Returns null on success, or the engine error. */
    suspend fun updateGeoDatabases(): String?

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun setLogObserver(observer: ILogObserver?)
}
