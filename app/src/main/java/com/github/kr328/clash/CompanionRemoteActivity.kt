package com.github.kr328.clash

import android.text.format.DateUtils
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.companion.CompanionStore
import com.github.kr328.clash.companion.controller.CompanionClient
import com.github.kr328.clash.companion.controller.CompanionError
import com.github.kr328.clash.companion.controller.CompanionEvent
import com.github.kr328.clash.companion.controller.CompanionEventFollower
import com.github.kr328.clash.companion.controller.ControllerStore
import com.github.kr328.clash.companion.controller.RemoteSetting
import com.github.kr328.clash.companion.controller.SettingValue
import com.github.kr328.clash.companion.controller.isUnreachable
import com.github.kr328.clash.companion.protocol.ActionInfo
import com.github.kr328.clash.companion.protocol.Capabilities
import com.github.kr328.clash.companion.protocol.Changed
import com.github.kr328.clash.companion.protocol.ConnectionList
import com.github.kr328.clash.companion.protocol.Delay
import com.github.kr328.clash.companion.protocol.Events
import com.github.kr328.clash.companion.protocol.ModeState
import com.github.kr328.clash.companion.protocol.ProfileInfo
import com.github.kr328.clash.companion.protocol.ProfileList
import com.github.kr328.clash.companion.protocol.ProviderInfo
import com.github.kr328.clash.companion.protocol.ProxyGroup
import com.github.kr328.clash.companion.protocol.Status
import com.github.kr328.clash.companion.protocol.Usage
import com.github.kr328.clash.design.CompanionRemoteDesign
import com.github.kr328.clash.design.CompanionRemoteDesign.Request
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.RemoteActionRow
import com.github.kr328.clash.design.model.RemoteConnectionRow
import com.github.kr328.clash.design.model.RemoteLink
import com.github.kr328.clash.design.model.RemoteOverview
import com.github.kr328.clash.design.model.RemoteProfileRow
import com.github.kr328.clash.design.model.RemoteProviderRow
import com.github.kr328.clash.design.model.RemoteProxyGroup
import com.github.kr328.clash.design.model.RemoteProxyNode
import com.github.kr328.clash.design.model.RemoteSection
import com.github.kr328.clash.design.model.RemoteSettingRow
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remote control for one paired TV (PROTOCOL.md §8, §9.2). Every button goes to the TV through
 * [CompanionClient]; nothing here touches the phone's own tunnel. While the screen is visible one
 * event stream keeps it live: `status` updates the overview, `changed` refetches the resource it
 * names, `traffic` drives the speed line. The stream stops in onStop.
 */
class CompanionRemoteActivity : BaseActivity<CompanionRemoteDesign>() {
    private val store by lazy { ControllerStore(this) }
    private lateinit var agent: ControllerStore.PairedAgent
    private lateinit var client: CompanionClient
    private lateinit var remote: CompanionRemoteDesign

    private var session: Job? = null
    private var caps: Set<String> = emptySet()
    private var status: Status? = null
    private var modeState: ModeState? = null
    private var profiles: ProfileList? = null
    private var groups: List<ProxyGroup> = emptyList()
    private var connectionsRefetch: Job? = null
    private var lastConnectionsFetch = 0L

    override suspend fun main() {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val stored = deviceId?.let(store::get)
        if (stored == null) {
            finish()
            return
        }
        agent = stored
        val self = CompanionStore(this)
        client = CompanionClient(agent, self.deviceId, self.displayName)
        remote = CompanionRemoteDesign(this, agent.name)
        setContentDesign(remote)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> startSession()
                        Event.ActivityStop -> stopSession()
                        else -> Unit
                    }
                }
                remote.requests.onReceive { handle(it) }
            }
        }
    }

    // --- session: one event stream while visible ----------------------------------------------

    private fun startSession() {
        if (session?.isActive == true) return
        session = launch { runSession() }
    }

    private fun stopSession() {
        session?.cancel()
        session = null
        launch { remote.setSpeed(null, null) }
    }

    private fun restartSession() {
        stopSession()
        if (activityStarted) startSession()
    }

    private suspend fun runSession() {
        remote.setLink(RemoteLink.Connecting)
        var backoff = 1_000L
        // The first status call learns the capabilities (§8.3), including whether `events` exists.
        while (true) {
            val st = try {
                io { client.status() }
            } catch (e: CompanionError) {
                if (!onRejected(e)) return
                null
            } catch (e: Exception) {
                Log.w("Companion remote: status ${agent.host}:${agent.port} failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (st != null) {
                applyStatus(st)
                break
            }
            remote.setLink(RemoteLink.Unreachable)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        if (Capabilities.EVENTS !in caps) {
            // No live events: refresh what is shown, then poll the status now and then.
            remote.setLink(RemoteLink.Live)
            refetchShown()
            while (currentCoroutineContext().isActive) {
                delay(POLL_MS)
                val polled = runCatching { io { client.status() } }
                polled.getOrNull()?.let { applyStatus(it) }
                val error = polled.exceptionOrNull()
                if (error is CompanionError && !onRejected(error)) return
            }
            return
        }

        var failures = 0
        val topics = if (Capabilities.TRAFFIC in caps) listOf(Events.TOPIC_TRAFFIC) else emptyList()
        CompanionEventFollower(client, topics).run(
            onState = { state, failure ->
                when (state) {
                    CompanionEventFollower.State.Live -> {
                        failures = 0
                        remote.setLink(RemoteLink.Live)
                    }
                    CompanionEventFollower.State.Connecting -> remote.setLink(RemoteLink.Connecting)
                    CompanionEventFollower.State.Reconnecting -> {
                        if (failure != null) {
                            failures++
                            Log.d("Companion remote: event stream dropped: ${failure.javaClass.simpleName}: ${failure.message}")
                        }
                        remote.setLink(if (failures >= UNREACHABLE_AFTER) RemoteLink.Unreachable else RemoteLink.Reconnecting)
                        remote.setSpeed(null, null)
                    }
                }
            },
            onOpen = { refetchShown() },
            onEvent = { onEvent(it) },
            onRejected = { onRejected(it) },
        )
    }

    /** The agent refused us: `unauthorized` means the TV revoked this phone (§7.3) — stop trying. */
    private suspend fun onRejected(e: CompanionError): Boolean {
        if (e.code != "unauthorized") return true
        remote.setLink(RemoteLink.Unreachable)
        remote.showToast(R.string.remote_unpaired_by_tv, ToastDuration.Long)
        return false
    }

    private suspend fun onEvent(event: CompanionEvent) {
        when (event) {
            is CompanionEvent.StatusChanged -> applyStatus(event.status)
            is CompanionEvent.ResourceChanged -> onChanged(event.resource)
            is CompanionEvent.TrafficTick -> remote.setSpeed(
                event.traffic.down.toBytesString(),
                event.traffic.up.toBytesString(),
            )
            is CompanionEvent.Log -> Unit
        }
    }

    private suspend fun onChanged(resource: String) {
        when (resource) {
            Changed.MODE -> refetchMode()
            Changed.PROFILES -> {
                refetchProfiles()
                if (remote.section == RemoteSection.Profiles) showProfiles()
            }
            Changed.PROXIES -> if (remote.nodePickerOpen) refetchProxies()
            Changed.PROVIDERS -> if (remote.section == RemoteSection.Providers) showSection(RemoteSection.Providers)
            Changed.SETTINGS -> if (remote.section == RemoteSection.Settings) showSection(RemoteSection.Settings)
            Changed.CONNECTIONS -> if (remote.section == RemoteSection.Connections) scheduleConnections()
            else -> Unit // unknown resources are ignored (§9.2)
        }
    }

    /** Connections change constantly on a busy TV; refetch at most once a second. */
    private fun scheduleConnections() {
        if (connectionsRefetch?.isActive == true) return
        connectionsRefetch = launch {
            val wait = CONNECTIONS_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastConnectionsFetch)
            if (wait > 0) delay(wait)
            showSection(RemoteSection.Connections)
        }
    }

    /** After every (re)connect: events are not replayed, so fetch what is on screen (§9.2). */
    private suspend fun refetchShown() {
        runCatching { io { client.status() } }.getOrNull()?.let { applyStatus(it) }
        refetchMode()
        refetchProfiles()
        remote.section?.let { showSection(it) }
        if (remote.nodePickerOpen) refetchProxies()
    }

    private suspend fun applyStatus(st: Status) {
        val firstOrChanged = status?.capabilities != st.capabilities
        status = st
        caps = st.capabilities.toSet()
        if (st.name != agent.name) {
            agent = agent.copy(name = st.name)
            store.put(agent)
            remote.setName(st.name)
        }
        if (firstOrChanged) {
            remote.setCapabilities(
                power = Capabilities.POWER in caps,
                mode = Capabilities.MODE in caps,
                nodes = Capabilities.PROXIES in caps,
                select = Capabilities.SELECT in caps,
                ping = Capabilities.DELAY in caps,
                profileUpdate = Capabilities.PROFILE_UPDATE in caps,
                traffic = Capabilities.TRAFFIC in caps,
                sections = buildList {
                    if (Capabilities.PROFILES in caps) add(RemoteSection.Profiles)
                    if (Capabilities.CONNECTIONS in caps) add(RemoteSection.Connections)
                    if (Capabilities.PROVIDERS in caps) add(RemoteSection.Providers)
                    if (Capabilities.SETTINGS in caps) add(RemoteSection.Settings)
                    if (Capabilities.ACTIONS in caps) add(RemoteSection.Actions)
                },
            )
        }
        renderOverview()
    }

    private suspend fun renderOverview() {
        val st = status ?: return
        val summary = st.summary
        val active = profiles?.let { list -> list.profiles.firstOrNull { it.id == list.active } }
        remote.setOverview(
            RemoteOverview(
                power = st.power == "on",
                mode = modeState?.mode ?: summary?.mode,
                modes = modeState?.modes ?: DEFAULT_MODES,
                profileName = active?.name ?: summary?.profile,
                profileMeta = active?.let(::profileMeta),
                profileUpdatable = active?.type == ProfileInfo.REMOTE,
                node = summary?.proxy,
            ),
        )
    }

    private suspend fun refetchMode() {
        if (Capabilities.MODE !in caps) return
        runCatching { io { client.mode() } }.getOrNull()?.let {
            modeState = it
            renderOverview()
        }
    }

    private suspend fun refetchProfiles() {
        if (Capabilities.PROFILES !in caps) return
        runCatching { io { client.profiles() } }.getOrNull()?.let {
            profiles = it
            renderOverview()
        }
    }

    private suspend fun refetchProxies() {
        val fresh = runCatching { io { client.proxies() } }.getOrNull() ?: return
        groups = fresh.groups
        remote.updateNodePicker(groups.map(::toRemote))
    }

    // --- sections ------------------------------------------------------------------------------

    private suspend fun showSection(section: RemoteSection) {
        try {
            when (section) {
                RemoteSection.Profiles -> {
                    profiles = io { client.profiles() }
                    renderOverview()
                    showProfiles()
                }
                RemoteSection.Connections -> {
                    lastConnectionsFetch = System.currentTimeMillis()
                    showConnections(io { client.connections() })
                }
                RemoteSection.Providers -> remote.setProviders(io { client.providers() }.map(::providerRow))
                RemoteSection.Settings -> remote.setSettings(io { client.settings() }.map(::settingRow))
                RemoteSection.Actions -> remote.setActions(io { client.actions() }.map(::actionRow))
            }
        } catch (e: Exception) {
            remote.setSectionError(section, describe(e))
        }
    }

    private suspend fun showProfiles() {
        val list = profiles ?: return
        remote.setProfiles(
            list.profiles.map { p ->
                RemoteProfileRow(p.id, p.name, profileMeta(p), p.id == list.active, p.type == ProfileInfo.REMOTE)
            },
        )
    }

    private suspend fun showConnections(list: ConnectionList) {
        val rows = list.connections.sortedByDescending { it.start }.map { c ->
            RemoteConnectionRow(
                id = c.id,
                title = c.host,
                subtitle = listOfNotNull(
                    c.chains.firstOrNull(),
                    c.rule.takeIf { it.isNotBlank() },
                    c.process.takeIf { it.isNotBlank() },
                    "↓ ${c.download.toBytesString()} ↑ ${c.upload.toBytesString()}",
                ).joinToString(" · "),
                network = c.network,
            )
        }
        remote.setConnections(rows, "↓ ${list.downloadTotal.toBytesString()} ↑ ${list.uploadTotal.toBytesString()}")
    }

    // --- buttons -------------------------------------------------------------------------------

    private fun handle(request: Request) {
        when (request) {
            Request.Power -> call {
                // An explicit target from the state on screen: a double tap or a stale view must
                // not flip the TV twice, as a blind toggle would.
                val now = client.power(if (status?.power == "on") "off" else "on")
                status = status?.copy(power = now)
                renderOverview()
            }
            is Request.SetMode -> call(onError = { renderOverview() }) {
                val mode = client.setMode(request.mode)
                modeState = modeState?.copy(mode = mode) ?: ModeState(mode, listOf(mode))
                renderOverview()
            }
            Request.OpenNodes -> call {
                groups = client.proxies().groups
                remote.openNodePicker(groups.map(::toRemote), status?.summary?.proxy)
            }
            Request.NodesClosed -> Unit
            is Request.SelectNode -> call(onError = { refetchProxies() }) {
                client.select(request.group, request.node)
                groups = groups.map { g -> if (g.name == request.group) g.copy(now = request.node) else g }
                remote.updateNodePicker(groups.map(::toRemote))
            }
            is Request.PingGroup -> call(finally = { remote.groupPingFinished(request.group) }) {
                mergeDelays(request.group, client.delay(request.group))
            }
            is Request.PingNode -> call(finally = { remote.nodePingFinished(request.group, request.node) }) {
                mergeDelays(request.group, client.delay(request.group, request.node))
            }
            Request.UpdateActiveProfile -> call {
                val list = profiles ?: client.profiles().also { profiles = it }
                val id = list.active.takeIf { it.isNotEmpty() } ?: return@call
                remote.showToast(R.string.remote_updating, ToastDuration.Short)
                client.updateProfile(id)
                remote.showToast(R.string.remote_done, ToastDuration.Short)
            }
            is Request.ShowSection -> launch { showSection(request.section) }
            is Request.SelectProfile -> call {
                client.selectProfile(request.id)
                refetchProfiles()
                showProfiles()
            }
            is Request.UpdateProfile -> call {
                remote.showToast(R.string.remote_updating, ToastDuration.Short)
                client.updateProfile(request.id)
                remote.showToast(R.string.remote_done, ToastDuration.Short)
                refetchProfiles()
                showProfiles()
            }
            is Request.CloseConnection -> call {
                client.closeConnections(request.id)
                showSection(RemoteSection.Connections)
            }
            is Request.UpdateProvider -> call {
                client.updateProvider(request.kind, request.name)
                remote.showToast(R.string.remote_done, ToastDuration.Short)
                showSection(RemoteSection.Providers)
            }
            is Request.HealthCheckProvider -> call {
                client.healthCheckProvider(request.name)
                remote.showToast(R.string.remote_done, ToastDuration.Short)
            }
            is Request.SetBool -> call(onError = { showSection(RemoteSection.Settings) }) {
                client.setSetting(request.key, SettingValue.Bool(request.value))
                showSection(RemoteSection.Settings)
            }
            is Request.SetEnum -> call(onError = { showSection(RemoteSection.Settings) }) {
                client.setSetting(request.key, SettingValue.Str(request.value))
                showSection(RemoteSection.Settings)
            }
            is Request.RunAction -> call {
                client.runAction(request.id)
                remote.showToast(R.string.remote_done, ToastDuration.Short)
            }
            Request.Retry -> restartSession()
            Request.Share -> launch { shareSubscription() }
            is Request.Rename -> call {
                client.rename(request.name)
                agent = agent.copy(name = request.name)
                store.put(agent)
                remote.setName(request.name)
                remote.showToast(R.string.companion_renamed, ToastDuration.Short)
            }
            Request.Forget -> launch { forget() }
        }
    }

    /** Runs one button press off the main thread and reports a failure as a toast. */
    private fun call(
        onError: suspend () -> Unit = {},
        finally: suspend () -> Unit = {},
        block: suspend CompanionClient.() -> Unit,
    ) = launch {
        try {
            withContext(Dispatchers.IO) { client.block() }
        } catch (e: Exception) {
            Log.w("Companion remote: request to ${agent.host}:${agent.port} failed: ${e.javaClass.simpleName}: ${e.message}")
            if (e is CompanionError && e.code == "unauthorized") {
                onRejected(e)
            } else {
                remote.showToast(describe(e), ToastDuration.Long)
            }
            onError()
        } finally {
            finally()
        }
    }

    private suspend fun mergeDelays(group: String, delays: List<Delay>) {
        val byName = delays.associate { it.name to it.delay }
        groups = groups.map { g ->
            g.copy(nodes = g.nodes.map { n -> byName[n.name]?.let { n.copy(delay = it) } ?: n })
        }
        Log.d("Companion remote: ${delays.size} delays for $group")
        remote.updateNodePicker(groups.map(::toRemote))
    }

    /** Sends the active subscription — unless its operator locked share links (share-links policy). */
    private suspend fun shareSubscription() {
        val active = withProfile { queryActive() }
        if (active == null || active.type != Profile.Type.Url || active.source.isBlank()) {
            remote.showToast(R.string.companion_no_shareable, ToastDuration.Long)
            return
        }
        if (ServiceStore(this).subscriptionShareLinksLockedFor(active.uuid)) {
            remote.showToast(R.string.remote_share_locked, ToastDuration.Long)
            return
        }
        try {
            io { client.subscription(active.source, active.name) }
            remote.showToast(R.string.companion_shared, ToastDuration.Short)
        } catch (e: Exception) {
            remote.showToast(describe(e), ToastDuration.Long)
        }
    }

    /**
     * "Forget" = revoke on the TV first (§7.4) so the token does not stay valid there, then
     * delete it here — even when the TV cannot be reached.
     */
    private suspend fun forget() {
        stopSession()
        remote.closeNodePicker()
        // The local token goes no matter what: leaving the screen mid-request cancels this
        // coroutine, and the token must not survive a "Forget" that the user already pressed.
        val revoked = try {
            withTimeoutOrNull(FORGET_TIMEOUT_MS) {
                runCatching { io { client.unpair() } }
                    .onFailure { Log.w("Companion remote: unpair ${agent.host}:${agent.port} failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .isSuccess
            } == true
        } finally {
            withContext(NonCancellable) { store.remove(agent.deviceId) }
        }
        if (revoked) {
            remote.showToast(getString(R.string.remote_forgotten, agent.name), ToastDuration.Short)
        } else {
            remote.showToast(R.string.remote_forgotten_offline, ToastDuration.Long)
        }
        delay(FORGET_FINISH_DELAY_MS)
        finish()
    }

    // --- mapping -------------------------------------------------------------------------------

    private fun describe(e: Throwable): String = when {
        e is CompanionError -> e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.companion_request_failed)
        isUnreachable(e) -> getString(R.string.companion_unreachable)
        else -> getString(R.string.companion_request_failed)
    }

    private fun toRemote(g: ProxyGroup) = RemoteProxyGroup(
        name = g.name,
        type = g.type,
        now = g.now,
        selectable = g.selectable,
        nodes = g.nodes.map { RemoteProxyNode(it.name, it.type, it.delay) },
    )

    private fun profileMeta(p: ProfileInfo): String = buildList {
        add(getString(if (p.type == ProfileInfo.REMOTE) R.string.url else R.string.file))
        p.usage?.let { u ->
            daysLeft(u)?.let { add("${getString(R.string.sub_announcement_days_left)} $it") }
            add(usageText(u))
        }
        updatedText(p.updatedAt)?.let(::add)
    }.joinToString(" • ")

    private fun daysLeft(u: Usage): Long? {
        if (u.expire <= 0) return null
        val left = u.expire * 1000L - System.currentTimeMillis()
        return if (left <= 0) 0 else (left + DAY_MS - 1) / DAY_MS
    }

    private fun usageText(u: Usage): String {
        val used = (u.upload + u.download).toBytesString()
        val total = if (u.total < 2L) getString(R.string.sub_announcement_unlimited) else u.total.toBytesString()
        return "$used / $total"
    }

    private fun updatedText(seconds: Long): String? {
        if (seconds <= 0) return null
        val ago = DateUtils.getRelativeTimeSpanString(seconds * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return getString(R.string.profile_updated_fmt, ago)
    }

    private fun providerRow(p: ProviderInfo) = RemoteProviderRow(
        kind = p.kind,
        name = p.name,
        meta = listOfNotNull(
            getString(if (p.kind == ProviderInfo.RULE) R.string.remote_kind_rule else R.string.remote_kind_proxy),
            p.vehicle,
            getString(R.string.remote_provider_items, p.count),
            updatedText(p.updatedAt),
        ).joinToString(" · "),
        updatable = p.vehicle != VEHICLE_INLINE,
        healthCheckable = p.kind == ProviderInfo.PROXY,
    )

    private fun settingRow(s: RemoteSetting) = RemoteSettingRow(
        key = s.key,
        label = s.label?.takeIf { it.isNotBlank() } ?: SETTING_LABELS[s.key]?.let(::getString) ?: s.key,
        checked = (s.value as? SettingValue.Bool)?.value,
        value = (s.value as? SettingValue.Str)?.value,
        options = s.options,
    )

    private fun actionRow(a: ActionInfo) = RemoteActionRow(
        id = a.id,
        label = a.label?.takeIf { it.isNotBlank() } ?: ACTION_LABELS[a.id]?.let(::getString) ?: a.id,
    )

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val EXTRA_DEVICE_ID = "companion_device_id"

        private const val MAX_BACKOFF_MS = 30_000L
        private const val POLL_MS = 10_000L
        private const val UNREACHABLE_AFTER = 3
        private const val CONNECTIONS_MIN_INTERVAL_MS = 1_000L
        private const val FORGET_TIMEOUT_MS = 6_000L
        private const val FORGET_FINISH_DELAY_MS = 900L
        private const val DAY_MS = 86_400_000L
        private const val VEHICLE_INLINE = "inline"
        private val DEFAULT_MODES = listOf("rule", "global")

        /** Well-known keys of §8.18; consumer-defined keys bring their own label. */
        private val SETTING_LABELS = mapOf(
            "tun" to R.string.remote_setting_tun,
            "system_proxy" to R.string.remote_setting_system_proxy,
            "allow_lan" to R.string.remote_setting_allow_lan,
            "ipv6" to R.string.remote_setting_ipv6,
            "sniffing" to R.string.remote_setting_sniffing,
            "log_level" to R.string.remote_setting_log_level,
        )

        /** Well-known ids of §8.19. */
        private val ACTION_LABELS = mapOf(
            "flush_dns" to R.string.remote_action_flush_dns,
            "flush_fakeip" to R.string.remote_action_flush_fakeip,
            "update_geo" to R.string.remote_action_update_geo,
            "restart_core" to R.string.remote_action_restart_core,
            "healthcheck_all" to R.string.remote_action_healthcheck_all,
        )
    }
}
