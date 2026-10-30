package com.github.kr328.clash

import android.app.Activity
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.core.model.DesyncCatalog
import com.github.kr328.clash.core.model.DesyncStatus
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.model.DesyncRuntimeSummary
import com.github.kr328.clash.design.model.DesyncStrategies
import com.github.kr328.clash.design.util.DesyncLabels
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.DesyncProfileSettings
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.TunnelModeApplier
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Home dashboard state for [MainActivity]: the full [MainDesign] refresh (service status, tunnel
 * mode, profiles, proxy groups and previews), visible-group detail fetches, traffic counters,
 * tunnel-mode switching and the Connect flow (profile preparation, VPN permission, post-start
 * warmup). Owns the stop-revive latch and pending tunnel-mode state those paths share.
 */
internal class MainDashboard(private val activity: MainActivity) {
    private val design: MainDesign?
        get() = activity.mainDesign

    private val uiStore
        get() = activity.mainUiStore

    private val clashRunning: Boolean
        get() = activity.isClashRunning

    private val runtime: MainProxyRuntime
        get() = activity.proxyRuntime

    internal data class HomeRuntimeSnapshot(
        val running: Boolean,
        val activeProfileUuid: UUID?,
        val proxyNames: List<String>,
    )

    internal var lastForwardedTrafficTotal: Long = Long.MIN_VALUE

    // When the user requests a stop, the service keeps reporting serviceRunning=true for a
    // second or two while it tears down. A dashboard refresh landing in that window would
    // read the stale "still running" snapshot and flip the power button back ON, so the
    // button visibly bounces off -> on -> (secs later) off — the "disconnect lag". While
    // this deadline is in the future we suppress reviving the button to ON; it clears as
    // soon as the service actually reports stopped (or on a new start, or after the
    // deadline as a safety net).
    internal var suppressRunningReviveUntil: Long = 0L
    private var pendingTunnelMode: TunnelState.Mode? = null

    /**
     * A paired controller changed the mode through [TunnelModeApplier]: drop any pick of ours that
     * is still converging so the next [fetch] shows the persisted (remote) mode instead of
     * re-asserting the stale one.
     */
    internal fun adoptExternalTunnelMode() {
        pendingTunnelMode = null
    }

    internal suspend fun MainDesign.patchTunnelMode(mode: TunnelState.Mode, scheduleRefresh: () -> Unit) {
        try {
            val normalized = runtime.normalizeTunnelMode(mode)
            pendingTunnelMode = normalized
            setMode(normalized)

            val running = clashRunning || withContext(Dispatchers.IO) { runtime.resolveStatusSnapshot().serviceRunning }
            val error = runCatching {
                if (TunnelModeApplier.apply(activity, normalized, running, remote = false)) {
                    pendingTunnelMode = null
                }
            }.exceptionOrNull()

            if (error != null && error !is CancellationException) {
                pendingTunnelMode = null
                showExceptionToast(error as? Exception ?: Exception(error))
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

    internal suspend fun MainDesign.fetch(): HomeRuntimeSnapshot {
        val status = withContext(Dispatchers.IO) { runtime.resolveStatusSnapshot() }
        val running = status.serviceRunning
        Remote.broadcasts.clashRunning = running
        if (running && SystemClock.uptimeMillis() < suppressRunningReviveUntil) {
            // User just asked to stop; the service still reports running mid-teardown.
            // Keep the power button OFF instead of bouncing it back ON.
            setClashRunning(false)
        } else {
            if (!running) suppressRunningReviveUntil = 0L
            setClashRunning(running)
        }

        var state = if (running) {
            runCatching {
                withClash { queryTunnelState() }
            }.getOrElse {
                TunnelState(runtime.parseTunnelMode(uiStore.tunnelModePreference) ?: TunnelState.Mode.Rule)
            }
        } else {
            TunnelState(runtime.parseTunnelMode(uiStore.tunnelModePreference) ?: TunnelState.Mode.Rule)
        }
        val rawMode = state.mode
        state = TunnelState(runtime.normalizeTunnelMode(state.mode))
        if (running && rawMode != state.mode) {
            runCatching {
                withClash {
                    val o = queryOverride(Clash.OverrideSlot.Session)
                    o.mode = state.mode
                    patchOverride(Clash.OverrideSlot.Session, o)
                }
            }
        }

        if (running) {
            val pref = uiStore.tunnelModePreference
            if (pref.isNotEmpty()) {
                val desired = runtime.parseTunnelMode(pref)
                if (desired != null && state.mode != desired) {
                    withClash {
                        val o = queryOverride(Clash.OverrideSlot.Session)
                        o.mode = desired
                        patchOverride(Clash.OverrideSlot.Session, o)
                    }
                    state = withClash { queryTunnelState() }
                    state = TunnelState(runtime.normalizeTunnelMode(state.mode))
                }
            } else {
                uiStore.tunnelModePreference = runtime.normalizeTunnelMode(state.mode).name
            }

            val preferredMode = runtime.parseTunnelMode(uiStore.tunnelModePreference)
            if (preferredMode == TunnelState.Mode.Global || state.mode == TunnelState.Mode.Global) {
                runtime.ensureGlobalSelectionSafe()
            }
        }

        val pendingMode = pendingTunnelMode
        if (pendingMode != null) {
            setMode(pendingMode)
            if (state.mode == pendingMode) {
                pendingTunnelMode = null
            }
        } else {
            val preferredMode = if (running) runtime.parseTunnelMode(uiStore.tunnelModePreference) else null
            setMode(preferredMode ?: state.mode)
        }

        val active = withProfile { queryActive() }
        val profiles = withProfile { queryAll() }
        setProfileName(active?.name ?: status.currentProfile)
        patchProfiles(profiles)
        fetchDesyncStatus(active, running)
        activity.subscriptionMeta.applyActiveBrand()

        val proxyNames = if (running) {
            runCatching {
                // Visible-only here. ProfileAdapter.effectiveGroupsForProfile
                // applies a 1-hop heuristic on top to surface any hidden auto
                // subgroups (URLTest/Fallback/LoadBalance) that are direct
                // members of a visible root — covers the kaso.fyi pattern
                // (one visible select root, hidden auto children) without
                // flooding the pill bar with deeper internal infra.
                withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val activeUuid = active?.uuid
        val expandedSet = getExpandedProfileUuids()
        val summaryPreviewUuid = activeUuid ?: profiles.firstOrNull { it.imported }?.uuid
        val previewUuids = (expandedSet + listOfNotNull(summaryPreviewUuid)).toSet()
        val offlinePreviewByProfile = previewUuids.associateWith { uuid ->
            withProfile { readProxyGroupsPreview(uuid) }
        }
        val offlineSelectionsByProfile = previewUuids.associateWith { uuid ->
            withProfile { queryProxySelections(uuid) }
        }
        val transportInfoByProfile = previewUuids.associateWith { uuid ->
            withProfile { readProxyTransports(uuid) }
        }

        patchProxyGroups(
            proxyNames,
            running,
            state.mode,
            uiStore.proxyLastGroup,
            offlinePreviewByProfile,
            activeUuid,
            offlineSelectionsByProfile,
            transportInfoByProfile,
        )
        if (!running || activeUuid == null || proxyNames.isEmpty()) {
            clearProxyDetails()
        } else {
            // After connect the Home "Node" row needs the active profile's summary group's live
            // `now`, but nothing queries it until the user opens the picker — so it stayed blank for
            // seconds. Prime just that one group (O-07-safe: a single query, not a blanket warmup),
            // and only when it isn't already cached, so this is effectively one-shot per connect
            // rather than a per-refresh engine hit.
            val summaryGroup = active?.let { summaryGroupFor(it) }
            if (!summaryGroup.isNullOrBlank() && !hasLiveDetailForGroup(summaryGroup)) {
                val primed = runtime.refreshRuntimeGroupDetails(setOf(summaryGroup))
                if (primed.isNotEmpty()) {
                    patchProxyDetails(primed)
                    // The Home node row was already bound above with empty details; re-render it now
                    // that the summary group's `now` is loaded so it fills without user interaction.
                    patchProfiles(profiles)
                }
            }
        }
        syncThemeToggleIcon(uiStore.darkMode)

        return HomeRuntimeSnapshot(
            running = running,
            activeProfileUuid = activeUuid,
            proxyNames = proxyNames,
        )
    }

    internal suspend fun MainDesign.fetchVisibleProxyGroup(profile: Profile, group: String) {
        val status = withContext(Dispatchers.IO) { runtime.resolveStatusSnapshot() }
        Remote.broadcasts.clashRunning = status.serviceRunning
        if (!status.serviceRunning || group.isBlank()) {
            clearProxyDetails()
            return
        }

        val activeUuid = withProfile { queryActive()?.uuid }
        if (activeUuid != profile.uuid || !profile.imported) {
            return
        }

        val groupsToFetch = runtime.collectRuntimeGroupTree(group)
            .ifEmpty { linkedSetOf(group).filter { it.isNotBlank() }.toSet() }
        repeat(12) { attempt ->
            val details = runtime.refreshRuntimeGroupDetails(groupsToFetch)
            val rootDetail = details[group]
                ?: details.entries.firstOrNull { it.key.equals(group, ignoreCase = true) }?.value
            val emptyDynamic = rootDetail != null &&
                rootDetail.proxies.isEmpty() &&
                rootDetail.type.group &&
                rootDetail.type != Proxy.Type.Relay
            val retry = (details.isEmpty() || emptyDynamic) && attempt < 11
            if (!retry) {
                if (details.isNotEmpty() && withProfile { queryActive()?.uuid } == profile.uuid) {
                    patchProxyDetails(details)
                }
                return
            }
            delay(200L)
        }
    }

    /** Strategy presets are static per library version: read once per dashboard. */
    private var desyncCatalog: DesyncCatalog? = null
    private val desyncJson = Json { ignoreUnknownKeys = true }

    /**
     * Home card line of a Desync-only active profile: the strategy it runs (its own preset, or the
     * catalog default when automatic) and, while connected, whether the core has its desync proxies
     * loaded (`queryDesyncStatus`). Called on every dashboard refresh and by the Home-visible ticker.
     */
    internal suspend fun MainDesign.fetchDesyncStatus(
        profile: Profile? = null,
        running: Boolean = clashRunning,
    ) {
        val active = profile ?: withProfile { queryActive() }
        if (active == null || !active.isDesyncOnly) {
            setDesyncStatusLine(null)
            setDesyncOverlayLine(if (active != null && running) desyncOverlayLine() else null)
            return
        }
        setDesyncOverlayLine(null)
        // From the service, never Clash.desyncPresets() here: see IClashManager.queryDesyncCatalog.
        val full = desyncCatalog ?: runCatching {
            desyncJson.decodeFromString(DesyncCatalog.serializer(), withClash { queryDesyncCatalog() })
        }.getOrNull()?.also { desyncCatalog = it }
        val catalog = full?.presets
        val settings = DesyncProfileSettings.decode(active.desyncSettings) ?: DesyncProfileSettings()
        val name = if (settings.strategyPresetId.isBlank() && (catalog?.autoTcp?.size ?: 0) >= 2) {
            context.getString(R.string.desync_strategy_automatic_short)
        } else {
            DesyncLabels.strategyName(
                context,
                catalog?.let { DesyncStrategies.effectiveTcp(it, settings.strategyPresetId) },
            )
        }
        val status = if (running) {
            runCatching {
                desyncJson.decodeFromString(DesyncStatus.serializer(), withClash { queryDesyncStatus() })
            }.getOrNull()
        } else {
            null
        }
        val line = when (val summary = DesyncRuntimeSummary.of(running, status)) {
            DesyncRuntimeSummary.Stopped -> name
            DesyncRuntimeSummary.NotLoaded -> context.getString(R.string.desync_status_not_loaded, name)
            is DesyncRuntimeSummary.Active -> if (summary.needsVpn.isEmpty()) {
                context.getString(R.string.desync_status_active, name, summary.routes)
            } else {
                val services = summary.needsVpn.joinToString(", ") { route ->
                    full?.services?.firstOrNull { "desync-${it.id}" == route }?.name
                        ?: if (route == "desync-custom") context.getString(R.string.desync_custom_domains) else route
                }
                context.getString(R.string.desync_status_active_needs_vpn, name, summary.routes, services)
            }
        }
        setDesyncStatusLine(line)
    }

    /**
     * Profile meta line of a subscription with desync on top: the services that go direct now, or
     * that the core is still checking, or that all of them go through the VPN. Null without overlay
     * routes (desync is off for this profile).
     */
    private suspend fun MainDesign.desyncOverlayLine(): String? {
        val status = runCatching {
            desyncJson.decodeFromString(DesyncStatus.serializer(), withClash { queryDesyncStatus() })
        }.getOrNull() ?: return null
        val routes = status.routes.filter { it.overlay }
        if (routes.isEmpty()) return null
        val direct = routes.filter { it.working }
        return when {
            direct.isNotEmpty() -> {
                val full = desyncCatalog ?: runCatching {
                    desyncJson.decodeFromString(DesyncCatalog.serializer(), withClash { queryDesyncCatalog() })
                }.getOrNull()?.also { desyncCatalog = it }
                val names = direct.joinToString(", ") { route ->
                    full?.services?.firstOrNull { "desync-${it.id}" == route.name }?.name
                        ?: if (route.name == "desync-custom") context.getString(R.string.desync_custom_domains) else route.name
                }
                context.getString(R.string.desync_overlay_status_direct, names)
            }
            routes.any { !it.checked } -> context.getString(R.string.desync_overlay_status_checking)
            else -> context.getString(R.string.desync_overlay_status_none)
        }
    }

    internal suspend fun MainDesign.fetchTraffic() {
        withClash {
            val total = queryTrafficTotal()
            if (total != lastForwardedTrafficTotal) {
                lastForwardedTrafficTotal = total
                setForwarded(total)
            }
            val now = queryTrafficNow()
            setSpeed(now.trafficDownload(), now.trafficUpload())
        }
    }

    internal suspend fun MainDesign.startClash() {
        val active = prepareActiveProfileForStart()

        if (active == null) return

        val loadEpochSnapshot = runtime.profileLoadEpoch.get()
        try {
            val vpnRequest = activity.startClashService()
            if (vpnRequest != null) {
                val result = activity.startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == Activity.RESULT_OK) {
                    activity.startClashService()
                } else {
                    setTunnelStarting(false)
                    showToast(R.string.vpn_permission_denied, ToastDuration.Long)
                    return
                }
            }
            launch {
                val d = design
                try {
                    d?.setPingingProfile(active.uuid)
                    runtime.waitForProxyEngineReady()
                    runtime.waitForProfileLoadEpochAfter(loadEpochSnapshot)
                    d?.fetch()
                    // O-07: warm up ONLY the group the user last looked at (+ its nested auto
                    // subtree), not every auto group. The old blanket warmup url-tested 200-node
                    // groups on connect and saturated the engine ("infinite ping" + sluggish
                    // switch). Other groups warm lazily when opened (see visibleGroupChanged).
                    runtime.warmedGroups.clear()
                    val warmed = runtime.warmUpGroupSubtree(uiStore.proxyLastGroup)
                    d?.fetch()
                    // Force-push fresh delays into the UI immediately instead
                    // of waiting for the next dashboard ticker (interactive=2s,
                    // idle=30s). Mihomo health-check writes delays into
                    // proxy.LastDelayForTestUrl synchronously when warmup
                    // finishes; without this re-query the carriage stays blank
                    // until the timer rolls over. (A delay event channel would
                    // side-step this whole class of staleness — see
                    // docs/path-b-engine-parsing.md follow-ups.)
                    if (warmed.isNotEmpty()) {
                        val patches = runtime.refreshRuntimeGroupDetails(warmed)
                        if (patches.isNotEmpty()) {
                            d?.patchProxyDetails(patches)
                        }
                    }
                } finally {
                    d?.setPingingProfile(null)
                }
            }
        } catch (e: Exception) {
            setTunnelStarting(false)
            showToast(R.string.vpn_start_failed_plain, ToastDuration.Indefinite) {
                setAction(R.string.logs) {
                    activity.startActivity(LogsActivity::class.intent)
                }
            }
        }
    }

    private suspend fun MainDesign.prepareActiveProfileForStart(): Profile? {
        val service = ServiceStore(activity)
        service.seedDefaultGeoMirrors = true

        var active = withProfile { queryActive() }
        if (active == null) {
            val importedProfiles = withProfile { queryAll() }
                .filter { it.imported && !it.pending }
            if (importedProfiles.size == 1) {
                val only = importedProfiles.first()
                withProfile { setActive(only) }
                active = withProfile { queryActive() }
            }
        }

        if (active == null) {
            setTunnelStarting(false)
            showToast(R.string.start_no_subscription_plain, ToastDuration.Long) {
                setAction(R.string.main_add_subscription) {
                    activity.profileActions.showHomeImportSheet(this@prepareActiveProfileForStart)
                }
            }
            return null
        }

        if (!active.imported || active.pending) {
            setTunnelStarting(false)
            showToast(R.string.start_profile_not_ready_plain, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    design?.openProfilesTab()
                }
            }
            return null
        }

        if (shouldAutoRefreshBeforeStart(active)) {
            showToast(R.string.profile_auto_refreshing, ToastDuration.Short)
            val refreshed = runCatching {
                withProfile { update(active.uuid) }
            }.isSuccess
            if (!refreshed) {
                showToast(R.string.profile_auto_refresh_failed_starting_saved, ToastDuration.Long)
            }
        }

        return withProfile { queryActive() } ?: active
    }

    private fun shouldAutoRefreshBeforeStart(profile: Profile): Boolean {
        // Opt-out (#197): people on flaky or slow panels would rather connect now with the config
        // they already have than wait on a refresh they did not ask for. Scheduled updates
        // (ProfileReceiver alarms) are unaffected — this only governs the Connect tap.
        if (!uiStore.updateProfileBeforeConnect) return false
        if (profile.type != Profile.Type.Url || profile.source.isBlank()) return false
        val now = System.currentTimeMillis()
        val last = profile.updatedAt.takeIf { it > 0L } ?: return true
        val userInterval = profile.interval.takeIf { it >= TimeUnit.MINUTES.toMillis(15) }
        val interval = userInterval ?: TimeUnit.HOURS.toMillis(12)
        return now - last >= interval
    }
}
