package com.github.kr328.clash.main

import android.os.SystemClock
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class HomeRuntimeSnapshot(
    val running: Boolean,
    val activeProfileUuid: UUID?,
    val proxyNames: List<String>,
)

/**
 * Pulls the runtime state (service status, tunnel mode, profiles, proxy groups, brand,
 * traffic) into [MainDesign]. Owns the traffic memo and the stop latch that keeps the power
 * button from bouncing back ON while the service tears down.
 */
internal class MainDashboardFetcher(
    private val activity: MainActivity,
    private val tunnel: MainTunnelModeController,
    private val probe: MainProxyProbe,
) {
    private val uiStore get() = activity.store
    private val design get() = activity.currentDesign

    private var lastForwardedTrafficTotal: Long = Long.MIN_VALUE

    // When the user requests a stop, the service keeps reporting serviceRunning=true for a
    // second or two while it tears down. A dashboard refresh landing in that window would
    // read the stale "still running" snapshot and flip the power button back ON, so the
    // button visibly bounces off -> on -> (secs later) off — the "disconnect lag". While
    // this deadline is in the future we suppress reviving the button to ON; it clears as
    // soon as the service actually reports stopped (or on a new start, or after the
    // deadline as a safety net).
    var suppressRunningReviveUntil: Long = 0L

    /** A fresh design starts from its default traffic label; drop the memo so the next push is real. */
    fun resetForNewRun() {
        lastForwardedTrafficTotal = Long.MIN_VALUE
    }

    suspend fun fetch(design: MainDesign): HomeRuntimeSnapshot = design.fetchSnapshot()

    suspend fun fetchVisibleProxyGroup(design: MainDesign, profile: Profile, group: String) =
        design.fetchVisibleGroup(profile, group)

    suspend fun fetchTraffic(design: MainDesign) = design.fetchTrafficTotals()

    private suspend fun MainDesign.fetchSnapshot(): HomeRuntimeSnapshot {
        val status = withContext(Dispatchers.IO) { activity.resolveStatusSnapshot() }
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
                TunnelState(parseTunnelMode(uiStore.tunnelModePreference) ?: TunnelState.Mode.Rule)
            }
        } else {
            TunnelState(parseTunnelMode(uiStore.tunnelModePreference) ?: TunnelState.Mode.Rule)
        }
        val rawMode = state.mode
        state = TunnelState(normalizeTunnelMode(state.mode))
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
                val desired = parseTunnelMode(pref)
                if (desired != null && state.mode != desired) {
                    withClash {
                        val o = queryOverride(Clash.OverrideSlot.Session)
                        o.mode = desired
                        patchOverride(Clash.OverrideSlot.Session, o)
                    }
                    state = withClash { queryTunnelState() }
                    state = TunnelState(normalizeTunnelMode(state.mode))
                }
            } else {
                uiStore.tunnelModePreference = normalizeTunnelMode(state.mode).name
            }

            val preferredMode = parseTunnelMode(uiStore.tunnelModePreference)
            if (preferredMode == TunnelState.Mode.Global || state.mode == TunnelState.Mode.Global) {
                tunnel.ensureGlobalSelectionSafe()
            }
        }

        val pendingMode = tunnel.pendingTunnelMode
        if (pendingMode != null) {
            setMode(pendingMode)
            if (state.mode == pendingMode) {
                tunnel.pendingTunnelMode = null
            }
        } else {
            val preferredMode = if (running) parseTunnelMode(uiStore.tunnelModePreference) else null
            setMode(preferredMode ?: state.mode)
        }

        val active = withProfile { queryActive() }
        val profiles = withProfile { queryAll() }
        setProfileName(active?.name ?: status.currentProfile)
        patchProfiles(profiles)
        if (currentTab == MainDesign.MainTab.Routing) requests.trySend(MainDesign.Request.RefreshRouting)
        applyActiveBrand()

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
                val primed = probe.refreshRuntimeGroupDetails(setOf(summaryGroup))
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

    private suspend fun MainDesign.fetchVisibleGroup(profile: Profile, group: String) {
        val status = withContext(Dispatchers.IO) { activity.resolveStatusSnapshot() }
        Remote.broadcasts.clashRunning = status.serviceRunning
        if (!status.serviceRunning || group.isBlank()) {
            clearProxyDetails()
            return
        }

        val activeUuid = withProfile { queryActive()?.uuid }
        if (activeUuid != profile.uuid || !profile.imported) {
            return
        }

        val groupsToFetch = probe.collectRuntimeGroupTree(group)
            .ifEmpty { linkedSetOf(group).filter { it.isNotBlank() }.toSet() }
        repeat(12) { attempt ->
            val details = probe.refreshRuntimeGroupDetails(groupsToFetch)
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

    private suspend fun MainDesign.fetchTrafficTotals() {
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

    /**
     * Reads the persisted brand state and pushes a [BrandHolder] snapshot into
     * MainDesign. Picks the theme-appropriate cached logo. Called on every
     * UI refresh tick so the header reflects the latest operator brand without
     * the design layer having to know about persistence.
     */
    suspend fun applyActiveBrand() {
        val design = design as? com.github.kr328.clash.design.MainDesign ?: return
        // try/finally guarantees the recreate-check runs on EVERY exit path —
        // including the "no active profile" / "no brand for active profile"
        // branches. Otherwise a brand → no-brand switch sheds the UI layer but
        // leaves the theme overlay branded until the app is force-stopped.
        try {
            val activeUuid = runCatching { withProfile { queryActive() } }.getOrNull()?.uuid
            if (activeUuid == null) {
                design.applyBrand(com.github.kr328.clash.design.branding.BrandHolder.EMPTY)
                return
            }
            val json = runCatching { withProfile { readBrandJsonFor(activeUuid) } }.getOrNull()
            if (json.isNullOrBlank()) {
                design.applyBrand(com.github.kr328.clash.design.branding.BrandHolder.EMPTY)
                return
            }
            val manifest = com.github.kr328.clash.common.branding.BrandManifest.fromJson(json)
            val nightMode = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val logoPath = runCatching {
                withProfile { brandLogoPathFor(activeUuid, nightMode) }
            }.getOrNull()
            com.github.kr328.clash.common.log.Log.d(
                "applyActiveBrand: uuid=$activeUuid, nightMode=$nightMode, " +
                    "manifest.logoUrl=${manifest.logoUrl}, manifest.logoLightUrl=${manifest.logoLightUrl}, " +
                    "resolvedPath=$logoPath",
            )
            design.applyBrand(
                com.github.kr328.clash.design.branding.BrandHolder(
                    manifest = manifest,
                    logoPath = logoPath,
                ),
            )
        } finally {
            // Theme overlay is captured at design-inflation time. If the active profile's accent
            // diverges from what the current design's wrapper carries, resolve the dashboard loop
            // with a soft recreate so the new (or absent) harmonised palette flows into every
            // widget that reads ?attr/colorPrimary etc. — without ever destroying the Activity
            // (Android 16 ContentCapture SIGABRT). Runs on EVERY fetch, so switches, updates and
            // brand clears all converge through the same level-based check.
            if (com.github.kr328.clash.design.branding.BrandThemeApplier.accentStale(activity)) {
                activity.requestSoftRecreate()
            }
        }
    }
}
