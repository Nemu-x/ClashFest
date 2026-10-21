package com.github.kr328.clash.companion.agent

import android.content.Context
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.companion.protocol.ActionInfo
import com.github.kr328.clash.companion.protocol.ConnectionInfo
import com.github.kr328.clash.companion.protocol.ConnectionList
import com.github.kr328.clash.companion.protocol.Delay
import com.github.kr328.clash.companion.protocol.ModeState
import com.github.kr328.clash.companion.protocol.Modes
import com.github.kr328.clash.companion.protocol.ProfileInfo
import com.github.kr328.clash.companion.protocol.ProfileList
import com.github.kr328.clash.companion.protocol.ProviderInfo
import com.github.kr328.clash.companion.protocol.ProxyNode
import com.github.kr328.clash.companion.protocol.Setting
import com.github.kr328.clash.companion.protocol.Summary
import com.github.kr328.clash.companion.protocol.Traffic
import com.github.kr328.clash.companion.protocol.Usage
import com.github.kr328.clash.core.model.ConnectionsSnapshot
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProxyDelayObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendConnectionsChanged
import com.github.kr328.clash.util.AboutVersions
import com.github.kr328.clash.util.ImportRetry
import com.github.kr328.clash.util.TunnelModeApplier
import com.github.kr328.clash.util.applyUserProxySelection
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.github.kr328.clash.companion.protocol.ProxyGroup as WireGroup

/**
 * ClashFest's remote buttons (PROTOCOL.md §8.6–§8.19): every state-changing call goes through the
 * same code path as the on-device control, so the change is persisted and the app's own UI shows
 * it — [applyUserProxySelection] for a node pick, [TunnelModeApplier] for the mode,
 * `IProfileManager.setActive` / `update` for profiles, the Settings screen's [ServiceStore]
 * setters for settings. Nothing here writes to the core behind the app's back.
 *
 * The P1 operations (power, import, rename) stay with [p1]. Runs on the gateway's request threads,
 * so each call blocks on the app's own suspend APIs.
 */
class CompanionRemoteControl(
    private val context: Context,
    private val p1: CompanionHooks,
) : CompanionHooks by p1 {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun running(): Boolean = StatusClient(context).statusSnapshot().serviceRunning

    private fun requireEngine() {
        if (!running()) throw CompanionHooks.CoreUnavailable("VPN is not running")
    }

    private suspend fun activeProfile(): Profile? = withProfile { queryActive() }

    /** Operator policy of the active subscription (hidden Global mode, primary group). */
    private suspend fun policy(profile: Profile?): BrandManifest {
        val uuid = profile?.uuid ?: return BrandManifest()
        val raw = runCatching { withProfile { readBrandJsonFor(uuid) } }.getOrNull()
        return BrandManifest.fromJson(raw)
    }

    // --- status summary (§8.1) ----------------------------------------------------------------

    override fun summary(): Summary = blocking {
        val running = running()
        val active = runCatching { activeProfile() }.getOrNull()
        val mode = runCatching { TunnelModeApplier.current(context, running) }.getOrNull()
        val proxy = if (running && active != null && mode != null) {
            runCatching { currentNode(active, mode) }.getOrNull()
        } else {
            null
        }
        Summary(
            mode = mode?.let(::wireMode),
            profile = active?.name,
            proxy = proxy,
            appVersion = AboutVersions.appSemver,
            coreVersion = AboutVersions.coreTag,
        )
    }

    /**
     * The node the Home "Node" row shows: start at the operator's primary group (not in Global
     * mode), else the picker's last group, else the first group with a saved pick, else the first
     * group, and follow each group's `now` down to a leaf. In Global mode traffic leaves through
     * GLOBAL, which [com.github.kr328.clash.service.ClashManager.patchSelector] keeps pointed at the
     * group the user picked in.
     */
    private suspend fun currentNode(active: Profile, mode: TunnelState.Mode): String? {
        val ui = UiStore(context)
        val groups = withClash { queryProxyGroupNames(ui.proxyExcludeNotSelectable) }
        if (groups.isEmpty()) return null
        val preferred = if (mode == TunnelState.Mode.Global) {
            groups.firstOrNull { it.equals(GLOBAL, ignoreCase = true) }
        } else {
            val primary = policy(active).primaryProxyGroup?.takeIf { it.isNotBlank() }
            val saved = runCatching { withProfile { queryProxySelections(active.uuid) } }.getOrDefault(emptyMap())
            primary?.let { p -> groups.firstOrNull { it == p } }
                ?: ui.proxyLastGroup.takeIf { it in groups }
                ?: saved.entries.firstOrNull { it.value.isNotBlank() && it.key in groups }?.key
        } ?: groups.first()

        for (group in listOf(preferred) + groups.filterNot { it == preferred }) {
            leafOf(group)?.let { return it }
        }
        return null
    }

    private suspend fun leafOf(start: String): String? {
        val seen = HashSet<String>()
        var current = start
        while (seen.add(current)) {
            val group = withClash { queryProxyGroup(current, ProxySort.Default) }
            val now = group.now.takeIf { it.isNotBlank() } ?: return null
            if (current.equals(GLOBAL, ignoreCase = true) && isDirectOrReject(now)) return null
            val nowIsGroup = group.proxies.firstOrNull { it.name == now }?.type?.group == true
            if (!nowIsGroup) return now
            current = now
        }
        return null
    }

    // --- proxies (§8.7–§8.9) ------------------------------------------------------------------

    /** The groups the Home node picker lists, in its order, with hidden groups left out. */
    override fun proxyGroups(): List<WireGroup> = blocking {
        requireEngine()
        val ui = UiStore(context)
        val names = withClash { queryProxyGroupNames(ui.proxyExcludeNotSelectable) }
        names.mapNotNull { name ->
            val group = runCatching { withClash { queryProxyGroup(name, ui.proxySort) } }.getOrNull()
                ?: return@mapNotNull null
            WireGroup(
                name = name,
                type = group.type.name,
                now = group.now,
                fixed = "",
                selectable = group.type == Proxy.Type.Selector,
                nodes = group.proxies
                    // The picker never offers DIRECT/REJECT under GLOBAL.
                    .filterNot { name.equals(GLOBAL, ignoreCase = true) && isDirectOrReject(it) }
                    .map { ProxyNode(it.name, it.type.name, wireDelay(it.delay)) },
            )
        }
    }

    override fun selectProxy(group: String, node: String): String = blocking {
        requireEngine()
        val ui = UiStore(context)
        val visible = withClash { queryProxyGroupNames(ui.proxyExcludeNotSelectable) }
        if (group !in visible) throw CompanionHooks.NotFound("unknown group: $group")
        val detail = withClash { queryProxyGroup(group, ProxySort.Default) }
        if (detail.proxies.none { it.name == node }) throw CompanionHooks.NotFound("unknown node: $node")
        if (detail.type != Proxy.Type.Selector) throw CompanionHooks.Refused("group $group is not selectable")
        val active = activeProfile() ?: throw CompanionHooks.CoreUnavailable("no active profile")
        val ok = context.applyUserProxySelection(active.uuid, group, node, remote = true) { _, _ -> }
        if (!ok) throw CompanionHooks.Refused("the engine refused $node in $group")
        withClash { queryProxyGroup(group, ProxySort.Default) }.now
    }

    /**
     * The picker's own health checks: one node like its tap-to-ping, or the whole group like its
     * ping button (per-proxy URLTest). The engine applies the group's configured per-node timeout;
     * [timeoutMs] bounds how long the remote call waits for results (late ones report 0).
     */
    override fun testDelay(group: String, node: String?, url: String?, timeoutMs: Int): List<Delay> = blocking {
        requireEngine()
        val known = withClash { queryAllProxyGroupNamesIncludingHidden() }
        if (group !in known && !group.equals(GLOBAL, ignoreCase = true)) {
            throw CompanionHooks.NotFound("unknown group: $group")
        }
        val members = withClash { queryProxyGroup(group, ProxySort.Default) }.proxies.map { it.name }
        if (node != null && node !in members) throw CompanionHooks.NotFound("unknown node: $node")

        val results = ConcurrentHashMap<String, Int>()
        val observer = object : IProxyDelayObserver {
            override fun onDelay(grp: String, proxy: String, delayMs: Int, errMsg: String) {
                results[proxy] = if (errMsg.isNotEmpty()) 0 else wireDelay(delayMs)
            }

            override fun onComplete(error: String?) {
                if (error != null) Log.w("Companion delay test $group failed: $error")
            }
        }
        val budget = if (node != null) timeoutMs + SINGLE_DELAY_SLACK_MS else GROUP_DELAY_BUDGET_MS
        withTimeoutOrNull(budget) {
            withClash {
                if (node != null) {
                    healthCheckProxy(group, node, url.orEmpty(), observer)
                } else {
                    healthCheckPerProxy(group, url.orEmpty(), observer)
                }
            }
        }
        (if (node != null) listOf(node) else members).map { Delay(it, results[it] ?: 0) }
    }

    // --- mode (§8.10) -------------------------------------------------------------------------

    private suspend fun offeredModes(): List<String> {
        val hideGlobal = policy(runCatching { activeProfile() }.getOrNull()).hideGlobalMode == true
        return if (hideGlobal) listOf(Modes.RULE) else listOf(Modes.RULE, Modes.GLOBAL)
    }

    override fun mode(): ModeState = blocking {
        ModeState(wireMode(TunnelModeApplier.current(context, running())), offeredModes())
    }

    override fun setMode(mode: String): String = blocking {
        if (mode !in offeredModes()) throw CompanionHooks.Refused("mode $mode is not offered on this device")
        val target = if (mode == Modes.GLOBAL) TunnelState.Mode.Global else TunnelState.Mode.Rule
        TunnelModeApplier.apply(context, target, running = null, remote = true)
        wireMode(TunnelModeApplier.current(context, running()))
    }

    // --- profiles (§8.11–§8.12) ---------------------------------------------------------------

    override fun profiles(): ProfileList = blocking {
        val active = activeProfile()
        val all = withProfile { queryAll() }.filter { it.imported }
        ProfileList(
            active = active?.uuid?.toString().orEmpty(),
            profiles = all.map { p ->
                ProfileInfo(
                    id = p.uuid.toString(),
                    name = p.name,
                    type = if (p.type == Profile.Type.Url) ProfileInfo.REMOTE else ProfileInfo.LOCAL,
                    updatedAt = (p.updatedAt / 1000L).coerceAtLeast(0L),
                    usage = if (p.upload > 0 || p.download > 0 || p.total > 0 || p.expire > 0) {
                        Usage(p.upload, p.download, p.total, (p.expire / 1000L).coerceAtLeast(0L))
                    } else {
                        null
                    },
                )
            },
        )
    }

    private suspend fun profileById(id: String): Profile {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw CompanionHooks.NotFound("unknown profile: $id")
        return withProfile { queryByUUID(uuid) }?.takeIf { it.imported }
            ?: throw CompanionHooks.NotFound("unknown profile: $id")
    }

    /** Same as the profiles screen's "set active": activate, then keep GLOBAL usable. */
    override fun selectProfile(id: String): String = blocking {
        val profile = profileById(id)
        withProfile { setActive(profile) }
        TunnelModeApplier.ensureGlobalSelectionSafeWithRetry(context)
        activeProfile()?.uuid?.toString().orEmpty()
    }

    /** Same as the card's update button: a subscription refresh with the transient-error retry. */
    override fun updateProfile(id: String?): Unit = blocking {
        val targets = if (id != null) {
            val profile = profileById(id)
            if (profile.type != Profile.Type.Url) throw CompanionHooks.Refused("profile $id has no update URL")
            listOf(profile)
        } else {
            withProfile { queryAll() }.filter { it.imported && it.type == Profile.Type.Url }
        }
        var failure: Exception? = null
        for (profile in targets) {
            try {
                ImportRetry.withTransientRetry { withProfile { update(profile.uuid) } }
            } catch (e: Exception) {
                Log.w("Companion: update of ${profile.uuid} failed: ${e.message}")
                failure = e
            }
        }
        failure?.let { throw Exception("subscription update failed: ${it.message}", it) }
    }

    // --- providers (§8.14) --------------------------------------------------------------------

    override fun providers(): List<ProviderInfo> = blocking {
        requireEngine()
        withClash { queryProviders() }.map { p ->
            ProviderInfo(
                name = p.name,
                kind = if (p.type == Provider.Type.Proxy) ProviderInfo.PROXY else ProviderInfo.RULE,
                vehicle = when (p.vehicleType) {
                    Provider.VehicleType.HTTP -> "http"
                    Provider.VehicleType.Inline -> "inline"
                    Provider.VehicleType.File, Provider.VehicleType.Compatible -> "file"
                },
                count = p.count,
                updatedAt = (p.updatedAt / 1000L).coerceAtLeast(0L),
            )
        }
    }

    private suspend fun requireProvider(type: Provider.Type, name: String) {
        if (withClash { queryProviders() }.none { it.type == type && it.name == name }) {
            throw CompanionHooks.NotFound("unknown provider: $name")
        }
    }

    override fun updateProvider(kind: String, name: String): Unit = blocking {
        requireEngine()
        val type = if (kind == ProviderInfo.PROXY) Provider.Type.Proxy else Provider.Type.Rule
        requireProvider(type, name)
        withClash { updateProvider(type, name) }
    }

    override fun healthCheckProvider(name: String): Unit = blocking {
        requireEngine()
        requireProvider(Provider.Type.Proxy, name)
        // A group of the same name would take this entry point; refuse rather than test the group.
        if (name in withClash { queryAllProxyGroupNamesIncludingHidden() }) {
            throw CompanionHooks.Refused("provider $name shares its name with a group")
        }
        withClash { healthCheck(name) }
    }

    // --- connections & traffic (§8.15–§8.16) --------------------------------------------------

    override fun connections(): ConnectionList = blocking {
        requireEngine()
        val raw = withClash { queryConnectionsSnapshot() }
        val snapshot = json.decodeFromString(ConnectionsSnapshot.serializer(), raw)
        ConnectionList(
            uploadTotal = snapshot.uploadTotal,
            downloadTotal = snapshot.downloadTotal,
            connections = snapshot.connections.map { c ->
                val m = c.metadata
                val host = m?.host?.takeIf { it.isNotBlank() }
                    ?: m?.sniffHost?.takeIf { it.isNotBlank() }
                    ?: m?.destinationIP.orEmpty()
                ConnectionInfo(
                    id = c.id,
                    host = if (m?.destinationPort.isNullOrBlank()) host else "$host:${m!!.destinationPort}",
                    network = m?.network.orEmpty(),
                    process = m?.process.orEmpty(),
                    rule = if (c.rulePayload.isNotBlank()) "${c.rule}(${c.rulePayload})" else c.rule,
                    chains = c.chains,
                    upload = c.upload,
                    download = c.download,
                    start = CompanionWire.rfc3339Millis(c.start),
                )
            },
        )
    }

    /** Same calls as the Connections screen's close / close-all buttons. */
    override fun closeConnections(id: String?): Int = blocking {
        requireEngine()
        val closed = if (id != null) {
            if (!withClash { closeConnection(id) }) throw CompanionHooks.NotFound("unknown connection: $id")
            1
        } else {
            withClash { closeAllConnections() }
        }
        context.sendConnectionsChanged()
        closed
    }

    override fun traffic(): Traffic = blocking {
        requireEngine()
        val (up, down) = CompanionWire.unpackTraffic(withClash { queryTrafficNow() })
        val (upTotal, downTotal) = CompanionWire.unpackTraffic(withClash { queryTrafficTotal() })
        Traffic(up = up, down = down, upTotal = upTotal, downTotal = downTotal)
    }

    // --- settings (§8.18) ---------------------------------------------------------------------

    /**
     * Only safe, Android-side toggles: `ipv6` and the TUN stack. Never tun / allow_lan /
     * system_proxy — hardening forbids exposing the tunnel on the LAN. Sniffing is a tri-state
     * override in the app (keep / on / off), not a plain switch, so it is not offered.
     */
    override fun settings(): List<Setting> {
        val store = ServiceStore(context)
        return listOf(
            Setting(KEY_IPV6, Setting.BOOL, JsonPrimitive(store.allowIpv6)),
            Setting(
                key = KEY_TUN_STACK,
                type = Setting.ENUM,
                value = JsonPrimitive(store.tunStackMode.takeIf { it in TUN_STACKS } ?: TUN_STACKS[1]),
                options = TUN_STACKS,
                label = context.getString(R.string.tun_stack_mode),
            ),
        )
    }

    /**
     * Like the Network settings screen: these apply at the next tunnel start, and the screen locks
     * them while the VPN runs, so a change is refused then. An operator `X-Network-Stack` lock
     * on the active subscription wins over the stack setting, as it does on the device.
     */
    override fun setSetting(key: String, value: JsonPrimitive): Setting {
        if (running()) throw CompanionHooks.Refused("stop the VPN to change $key; it applies on the next connect")
        val store = ServiceStore(context)
        when (key) {
            KEY_IPV6 -> store.allowIpv6 = value.boolean
            KEY_TUN_STACK -> {
                val locked = store.activeProfile?.let { store.subscriptionNetworkStackFor(it) }
                    ?.trim()?.lowercase()
                if (locked != null && locked in OPERATOR_STACK_LOCKS) {
                    throw CompanionHooks.Refused("the TUN stack is locked to $locked by the subscription operator")
                }
                store.tunStackMode = value.content
            }
            else -> throw CompanionHooks.NotFound("unknown setting: $key")
        }
        return settings().first { it.key == key }
    }

    // --- actions (§8.19) ----------------------------------------------------------------------

    override fun actions(): List<ActionInfo> = listOf(
        ActionInfo(ACTION_HEALTHCHECK_ALL),
        ActionInfo(ACTION_RECONNECT, context.getString(R.string.companion_action_reconnect)),
    )

    override fun runAction(id: String): Unit = blocking {
        when (id) {
            ACTION_HEALTHCHECK_ALL -> {
                requireEngine()
                withClash { healthCheckAll() }
            }
            ACTION_RECONNECT -> reconnect()
            else -> throw CompanionHooks.NotFound("unknown action: $id")
        }
    }

    /** Restart the tunnel through the app's own stop/start, only when it is running. */
    private suspend fun reconnect() {
        if (!running()) throw CompanionHooks.Refused("the VPN is not running")
        context.stopClashService()
        val stopped = withTimeoutOrNull(RECONNECT_STOP_TIMEOUT_MS) {
            while (running()) delay(200L)
            true
        } ?: throw CompanionHooks.CoreUnavailable("the VPN did not stop in time")
        if (stopped && context.startClashService() != null) {
            throw CompanionHooks.CoreUnavailable("VPN permission not granted on the agent device")
        }
    }

    private companion object {
        const val GLOBAL = "GLOBAL"
        const val KEY_IPV6 = "ipv6"
        const val KEY_TUN_STACK = "tun_stack"
        val TUN_STACKS = listOf("auto", "system", "gvisor", "mixed", "mips")

        /** `X-Network-Stack` values that lock the stack (TunStackResolver); `auto` does not. */
        val OPERATOR_STACK_LOCKS = setOf("system", "gvisor", "mixed", "lwip", "mips")
        const val ACTION_HEALTHCHECK_ALL = "healthcheck_all"
        const val ACTION_RECONNECT = "reconnect"
        const val SINGLE_DELAY_SLACK_MS = 5_000L
        const val GROUP_DELAY_BUDGET_MS = 60_000L
        const val RECONNECT_STOP_TIMEOUT_MS = 15_000L

        fun wireMode(mode: TunnelState.Mode): String =
            if (TunnelModeApplier.normalize(mode) == TunnelState.Mode.Global) Modes.GLOBAL else Modes.RULE

        fun isDirectOrReject(name: String): Boolean =
            name.equals("DIRECT", ignoreCase = true) || name.equals("REJECT", ignoreCase = true)

        fun isDirectOrReject(proxy: Proxy): Boolean =
            proxy.type == Proxy.Type.Direct || proxy.type == Proxy.Type.Reject || isDirectOrReject(proxy.name)

        /** Engine delays: 0/65535/negative mean "unknown or failed", which the wire spells 0. */
        fun wireDelay(delay: Int): Int = if (delay in 1 until 65535) delay else 0
    }
}
