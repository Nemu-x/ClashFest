package com.github.kr328.clash.design.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.ClickGuard
import com.github.kr328.clash.design.databinding.AdapterProfileBinding
import com.github.kr328.clash.design.databinding.AdapterSubscriptionBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.ProfilePageState
import com.github.kr328.clash.design.model.ServerPingMode
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.model.ProxyTransportInfo
import com.google.android.material.color.MaterialColors
import java.util.UUID
import java.util.concurrent.TimeUnit

class ProfileAdapter(
    private val onClicked: (Profile) -> Unit,
    private val onMenuClicked: (Profile, View) -> Unit,
    private val onExpandToggle: (Profile) -> Unit = {},
    internal val onProxyNodeSelected: (Profile, String, String) -> Unit = { _, _, _ -> },
    internal val onPingAll: (Profile, String, List<String>, String) -> Unit = { _, _, _, _ -> },
    private val onForceUpdate: (Profile) -> Unit = {},
    internal val onProxyYamlDetail: (profile: Profile, groupName: String, proxyName: String) -> Unit =
        { _, _, _ -> },
    private val onVisibleGroupChanged: (Profile, String) -> Unit = { _, _ -> },
    /** Tap on a node's latency capsule: measure just that node (profile, group, proxy). */
    internal val onPingNode: (Profile, String, String) -> Unit = { _, _, _ -> },
    private val expandOnProfileClick: Boolean = false,
    private val compactSubscriptions: Boolean = false,
    /** The user's own picker layout choice ("tabs" / "dropdown"), "" when never toggled. */
    private val readProxyGroupLayout: () -> String = { "" },
    internal val writeProxyGroupLayout: (String) -> Unit = {},
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    sealed class Holder(view: View) : RecyclerView.ViewHolder(view)
    class ProfileHolder(val binding: AdapterProfileBinding) : Holder(binding.root)
    class SubscriptionHolder(val binding: AdapterSubscriptionBinding) : Holder(binding.root)

    var profiles: List<Profile> = emptyList()
    val states = ProfilePageState()
    private val subscriptionClickGuard = ClickGuard<Pair<UUID, Int>>(600L, SystemClock::elapsedRealtime)

    internal var proxyGroupNames: List<String> = emptyList()
    /** The `excludeNotSelectable` setting [proxyGroupNames] was queried with — see [setProxyContext]. */
    internal var excludeNotSelectable: Boolean = false
    /** Prefill for the latency-target dialog; the measurement target itself is never sticky. */
    internal var lastLatencyTarget: String = ""
    internal var serverPingMode = ServerPingMode.ThroughServer
    internal var proxyDetails: Map<String, ProxyGroup> = emptyMap()
    internal var activeProfileUuid: UUID? = null
    internal var clashRunning: Boolean = false
    internal var tunnelMode: TunnelState.Mode? = null
    internal var lastGroupHint: String? = null
    internal var primaryProxyGroup: String? = null
    internal var expandedUuids: Set<UUID> = emptySet()
    /** Offline proxy groups per profile (expanded cards that are not using live engine data). */
    internal var offlinePreviewByProfile: Map<UUID, Map<String, ProxyGroupPreviewRow>> = emptyMap()
    internal var offlineSelectionsByProfile: Map<UUID, Map<String, String>> = emptyMap()
    internal var transportInfoByProfile: Map<UUID, Map<String, ProxyTransportInfo>> = emptyMap()
    private val cachedOfflinePreviewByProfile = mutableMapOf<UUID, Map<String, ProxyGroupPreviewRow>>()
    private val cachedOfflineSelectionsByProfile = mutableMapOf<UUID, Map<String, String>>()
    internal val selectedGroupIndex = mutableMapOf<UUID, Int>()
    private val lastReportedVisibleGroup = mutableMapOf<UUID, String>()
    internal var proxySheetDialog: AppBottomSheetDialog? = null
    internal val pendingProxySelections = mutableMapOf<String, String>()
    /** Per-node ms when core is off: key `uuid|proxyName`. */
    internal val standalonePingDelays: MutableMap<String, Int> = mutableMapOf()

    // A live URL-test pushes one delay per proxy. With many nodes (e.g. 200) doing a full
    // active-card rebind per push froze the UI (the expanded card re-renders every node row each
    // time). We patch the data immediately but coalesce the rebind into one notify per window.
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Nodes whose single-node latency test is in flight (tap on the capsule). Keyed by proxy name:
     * the measurement is per proxy, whichever group row it was tapped in. Rows render "…" while
     * pending; the entry is dropped by the first result for that proxy (engine push or the offline
     * TCP fallback) or by the safety timeout, so a lost callback can never freeze the capsule.
     */
    private val pendingNodePings = HashSet<String>()

    /**
     * Installed by the open node-picker sheet: re-resolves and rebinds the row of one proxy so a
     * single-node result lands in the RecyclerView immediately, without waiting for the sheet's
     * ping-all tick (which only runs while a whole-group test is active).
     */
    internal var sheetDelayPatcher: ((proxyName: String) -> Unit)? = null
    /** Re-renders an open picker sheet when live group detail lands (header summaries / new rows). */
    internal var sheetDetailsRefresher: (() -> Unit)? = null
    /** Which group's "ping all" is in flight per profile, so the dropdown layout spins on the right header. */
    internal val pingingGroupByUuid = HashMap<UUID, String>()
    /** Operator default for the picker layout (`X-Brand-Proxy-Group-Layout`), used until the user toggles. */
    private var proxyGroupLayoutDefault: String? = null
    /** Expanded groups of the dropdown picker layout, per profile, kept across sheet opens. */
    internal val sheetExpandedGroups = HashMap<UUID, MutableSet<String>>()

    internal fun markNodePingPending(proxyName: String): Boolean {
        if (!pendingNodePings.add(proxyName)) return false
        mainHandler.postDelayed({ completeNodePing(proxyName) }, NODE_PING_PENDING_TIMEOUT_MS)
        return true
    }

    internal fun isNodePingPending(proxyName: String): Boolean = proxyName in pendingNodePings

    /** @return true when [proxyName] was pending (the caller should rebind its row). */
    fun completeNodePing(proxyName: String): Boolean {
        if (!pendingNodePings.remove(proxyName)) return false
        scheduleActiveCardNotify()
        sheetDelayPatcher?.invoke(proxyName)
        return true
    }
    private var activeCardNotifyScheduled = false
    private val activeCardNotifyRunnable = Runnable {
        activeCardNotifyScheduled = false
        val uuid = activeProfileUuid ?: return@Runnable
        val i = profiles.indexOfFirst { it.uuid == uuid }
        if (i >= 0) notifyItemChanged(i)
    }

    private fun scheduleActiveCardNotify() {
        if (activeCardNotifyScheduled) return
        activeCardNotifyScheduled = true
        mainHandler.postDelayed(activeCardNotifyRunnable, ACTIVE_CARD_NOTIFY_DEBOUNCE_MS)
    }
    /** Last time ping-all was triggered per profile (System.currentTimeMillis()). */
    internal val lastPingAllAt: MutableMap<UUID, Long> = mutableMapOf()

    /** Operator-supplied brand manifest, used by v2+ surfaces (Renew tap, max-devices chip). */
    private var brandManifest: BrandManifest = BrandManifest.EMPTY
    private var onOpenBrandUrl: ((String) -> Unit)? = null

    /** Operator-pushed announcement, rendered inline on the active profile card. */
    private var announcementText: String? = null
    private var announcementUrl: String? = null
    private var announcementSupportUrl: String? = null
    private var announcementOnOpenUrl: ((String) -> Unit)? = null
    private var announcementOnSupport: (() -> Unit)? = null
    /**
     * Push the latest operator brand into the adapter so v2 surfaces
     * (critical-expiry chip Renew tap, max-devices chip, etc.) can react.
     */
    fun setBrandManifest(manifest: BrandManifest, onOpenBrandUrl: (String) -> Unit) {
        val changed = manifest != brandManifest
        brandManifest = manifest
        this.onOpenBrandUrl = onOpenBrandUrl
        if (changed) notifyDataSetChanged()
    }

    /**
     * Operator `X-Brand-Primary-Proxy-Group` for the active profile: pins which group's current node
     * the summaries show. Ignored outside Rule/Direct mode (Global routes through GLOBAL) and when the
     * config has no such group.
     */
    fun setPrimaryProxyGroup(name: String?) {
        val cleaned = name?.takeIf { it.isNotBlank() }
        if (cleaned == primaryProxyGroup) return
        primaryProxyGroup = cleaned
        notifyDataSetChanged()
    }

    fun setProxyGroupLayoutDefault(layout: String?) {
        proxyGroupLayoutDefault = layout
    }

    internal fun isDropdownLayout(): Boolean {
        val layout = readProxyGroupLayout().ifBlank { proxyGroupLayoutDefault.orEmpty() }
        return layout == BrandManifest.PROXY_GROUP_LAYOUT_DROPDOWN
    }

    fun setActiveAnnouncement(
        text: String?,
        url: String?,
        supportUrl: String?,
        onOpenUrl: ((String) -> Unit)?,
        onSupport: (() -> Unit)?,
    ) {
        val raw = text?.takeIf { it.isNotBlank() }
        val decoded = raw?.let {
            com.github.kr328.clash.common.util.MaybeBase64.decode(it).takeIf { d -> d.isNotBlank() }
        }
        val changed =
            decoded != announcementText ||
                url != announcementUrl ||
                supportUrl != announcementSupportUrl
        announcementText = decoded
        announcementUrl = url?.takeIf { it.isNotBlank() }
        announcementSupportUrl = supportUrl?.takeIf { it.isNotBlank() }
        announcementOnOpenUrl = onOpenUrl
        announcementOnSupport = onSupport
        if (changed) {
            val active = activeProfileUuid ?: return
            val i = profiles.indexOfFirst { it.uuid == active }
            if (i >= 0) notifyItemChanged(i)
        }
    }

    fun updateElapsed() {
        // Full rebind is acceptable here: this is invoked from the profile
        // ticker (TimeUnit.MINUTES.toMillis(2)) — every two minutes, not on a
        // scroll-sensitive cadence. Moving it to a payload-based partial bind
        // would require splitting onBindViewHolder into time-dependent / time-
        // independent halves, which is not worth the maintenance cost for a
        // 0.5/min event.
        notifyDataSetChanged()
    }

    fun moveProfile(from: Int, to: Int): Boolean {
        if (from !in profiles.indices || to !in profiles.indices || from == to) return false
        profiles = profiles.toMutableList().apply {
            add(to, removeAt(from))
        }
        notifyItemMoved(from, to)
        return true
    }

    fun setPingingUuid(uuid: UUID?) {
        val prev = states.pingingUuid
        if (prev == uuid) return
        states.pingingUuid = uuid
        // Targeted notify instead of full-list rebind: ping spinners only affect at most
        // two cards (the one that was pinging and the one that just started).
        prev?.let { id ->
            val i = profiles.indexOfFirst { it.uuid == id }
            if (i >= 0) notifyItemChanged(i)
        }
        uuid?.let { id ->
            val i = profiles.indexOfFirst { it.uuid == id }
            if (i >= 0) notifyItemChanged(i)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        if (holder is ProfileHolder) {
            holder.binding.activeStatusChip.alpha = 1f
            holder.binding.pingProgress.visibility = View.GONE
        }
        super.onViewRecycled(holder)
    }

    fun setProxyContext(
        names: List<String>,
        running: Boolean,
        mode: TunnelState.Mode?,
        lastGroupHint: String?,
        offlinePreviewByProfile: Map<UUID, Map<String, ProxyGroupPreviewRow>> = emptyMap(),
        activeProfileUuid: UUID? = null,
        offlineSelectionsByProfile: Map<UUID, Map<String, String>> = emptyMap(),
        transportInfoByProfile: Map<UUID, Map<String, ProxyTransportInfo>> = emptyMap(),
        /**
         * The `excludeNotSelectable` flag [names] was queried with. The engine applies it in
         * `queryProxyGroupNames`; the offline preview has to apply the same rule itself, or the
         * pill bar changes shape the moment the VPN comes up.
         */
        excludeNotSelectable: Boolean = false,
    ) {
        if (names == proxyGroupNames && running == clashRunning && mode == tunnelMode &&
            excludeNotSelectable == this.excludeNotSelectable &&
            lastGroupHint == this.lastGroupHint &&
            offlinePreviewByProfile == this.offlinePreviewByProfile &&
            activeProfileUuid == this.activeProfileUuid &&
            offlineSelectionsByProfile == this.offlineSelectionsByProfile &&
            transportInfoByProfile == this.transportInfoByProfile
        ) {
            return
        }
        // Capture what was here so we can compute which profile cards actually
        // need a rebind. Doing a full notifyDataSetChanged from this hot path
        // (interactive dashboard ticker fires it every ~2s) was the main scroll
        // jank source: every visible card got rebound from scratch even when
        // only one profile's selection map changed.
        val previousActiveUuid = this.activeProfileUuid
        val previousMode = this.tunnelMode
        val previousLastGroupHint = this.lastGroupHint
        val previousOfflinePreview = this.offlinePreviewByProfile
        val previousOfflineSelections = this.offlineSelectionsByProfile
        val previousTransportInfo = this.transportInfoByProfile

        this.transportInfoByProfile = transportInfoByProfile
        // Only reset runtime proxies when identity actually changes. The core may return the
        // same selector list in a different order after patchSelector; treating that like a
        // full reset cleared pending selections and made the home card look like the tap lost.
        val groupSetChanged = names.toSet() != proxyGroupNames.toSet()
        val identityChanged =
            activeProfileUuid != this.activeProfileUuid || groupSetChanged
        val runningChanged = running != clashRunning
        val shouldResetRuntimeProxyState = runningChanged || identityChanged
        if (shouldResetRuntimeProxyState) {
            lastReportedVisibleGroup.clear()
            proxyDetails = emptyMap()
            // Keep pending across VPN off→on: engine `now` is wrong until applyPostLoad
            // finishes; pending matches the user's choice and avoids a first-frame flash.
            if (identityChanged) {
                pendingProxySelections.clear()
            }
        }
        // On a profile switch the engine keeps serving the PREVIOUS profile's config for a moment
        // (async reload), so `names` can still be the old profile's groups — which showed the old
        // profile's selectors/nodes in the picker until the reload landed. Detect that (fresh engine
        // groups share nothing with the NEW active profile's offline preview) and suppress the stale
        // list until reload completes; the picker then falls back to the new profile's offline groups
        // (useEngineFor requires proxyGroupNames non-empty) instead of showing the old profile's.
        val newActiveOfflineGroups = activeProfileUuid?.let { offlinePreviewByProfile[it]?.keys }.orEmpty()
        val engineStaleAfterSwitch = activeProfileUuid != previousActiveUuid && names.isNotEmpty() &&
            newActiveOfflineGroups.isNotEmpty() &&
            names.none { n -> newActiveOfflineGroups.any { groupsMatchKey(n, it) } }
        proxyGroupNames = if (engineStaleAfterSwitch) emptyList() else names
        this.excludeNotSelectable = excludeNotSelectable
        if (offlinePreviewByProfile.isNotEmpty()) {
            cachedOfflinePreviewByProfile.putAll(offlinePreviewByProfile)
        }
        if (offlineSelectionsByProfile.isNotEmpty()) {
            cachedOfflineSelectionsByProfile.putAll(offlineSelectionsByProfile)
        }
        this.offlinePreviewByProfile = cachedOfflinePreviewByProfile.toMap()
        this.offlineSelectionsByProfile = cachedOfflineSelectionsByProfile.toMap()
        this.activeProfileUuid = activeProfileUuid
        clashRunning = running
        tunnelMode = mode
        this.lastGroupHint = lastGroupHint
        dispatchProxyContextChanges(
            previousActiveUuid = previousActiveUuid,
            previousMode = previousMode,
            previousLastGroupHint = previousLastGroupHint,
            previousOfflinePreview = previousOfflinePreview,
            previousOfflineSelections = previousOfflineSelections,
            previousTransportInfo = previousTransportInfo,
            runningChanged = runningChanged,
            groupSetChanged = groupSetChanged,
        )
    }

    /**
     * Computes the set of profile cards actually affected by a setProxyContext
     * batch and issues a single notifyItemChanged per affected position.
     *
     * The rules are intentionally conservative — when in doubt we still notify,
     * because a missed update would look like a stale card. The point is to
     * avoid full notifyDataSetChanged for the common case where only the
     * active profile's selection / transport map changed: that case used to
     * rebind every visible card and was the dominant cause of scroll jank.
     */
    private fun dispatchProxyContextChanges(
        previousActiveUuid: UUID?,
        previousMode: TunnelState.Mode?,
        previousLastGroupHint: String?,
        previousOfflinePreview: Map<UUID, Map<String, ProxyGroupPreviewRow>>,
        previousOfflineSelections: Map<UUID, Map<String, String>>,
        previousTransportInfo: Map<UUID, Map<String, ProxyTransportInfo>>,
        runningChanged: Boolean,
        groupSetChanged: Boolean,
    ) {
        val affected = HashSet<UUID>()

        // Active-profile identity flips both the visual "this card is on" badge
        // and engine/offline routing — touch the old card and the new one.
        if (previousActiveUuid != activeProfileUuid) {
            previousActiveUuid?.let(affected::add)
            activeProfileUuid?.let(affected::add)
        }
        // Engine identity (running/group set/mode) influences the active card's
        // expanded carriage (engine path vs offline preview). Other cards
        // consult these fields only via useEngineFor() which short-circuits to
        // false for non-active profiles, so they don't need a rebind here.
        if (runningChanged || groupSetChanged || previousMode != tunnelMode) {
            activeProfileUuid?.let(affected::add)
        }
        // lastGroupHint is global — resolvePreferredGroupFromList() consults it
        // for every profile that hasn't yet cached a selectedGroupIndex (cards
        // that were never bound, e.g. still below the scroll viewport). If we
        // only touch the active card, a later scroll-into-view of card C would
        // bind it against the new hint while card A still shows the old one,
        // leaving the list with two different preferred groups across cards.
        // Hint changes are not on a hot path — they only fire when the user
        // explicitly switches the visible group — so rebinding every imported
        // card here is cheap insurance against that inconsistency.
        if (previousLastGroupHint != lastGroupHint) {
            for (p in profiles) {
                if (p.imported) affected.add(p.uuid)
            }
        }
        // Per-profile maps only affect the profile whose UUID actually moved.
        previousOfflinePreview.collectDiffKeys(offlinePreviewByProfile, affected)
        previousOfflineSelections.collectDiffKeys(offlineSelectionsByProfile, affected)
        previousTransportInfo.collectDiffKeys(transportInfoByProfile, affected)

        if (affected.isEmpty()) return
        for (i in profiles.indices) {
            if (profiles[i].uuid in affected) notifyItemChanged(i)
        }
    }

    private fun <K, V> Map<K, V>.collectDiffKeys(other: Map<K, V>, into: HashSet<K>) {
        for ((key, value) in this) {
            if (other[key] != value) into.add(key)
        }
        for ((key, value) in other) {
            if (this[key] != value) into.add(key)
        }
    }

    fun setProxyDetails(details: Map<String, ProxyGroup>) {
        if (details.isEmpty()) return
        val merged = proxyDetails.toMutableMap().apply {
            putAll(details)
        }
        val active = activeProfileUuid
        val hasFuzzyPending = active != null &&
            details.keys.any { group -> pendingMapValueForGroup(active, group) != null }
        val needsDiskOverlay = active != null && details.keys.any { group ->
            val d = merged[group] ?: return@any false
            val disk = offlineSelectedForGroup(active, group)
            disk.isNotBlank() && disk != d.now &&
                (d.proxies.isEmpty() || d.proxies.any { it.name == disk })
        }
        if (merged == proxyDetails && !hasFuzzyPending && !needsDiskOverlay) return
        proxyDetails = merged
        active?.let { uuid ->
            for ((group, detail) in details) {
                val prefix = "${uuid}|"
                val removeKeys = mutableListOf<String>()
                for (key in pendingProxySelections.keys) {
                    if (!key.startsWith(prefix)) continue
                    val g = key.substring(prefix.length)
                    if (!groupsMatchKey(group, g)) continue
                    val pending = pendingProxySelections[key] ?: continue
                    val pendingApplied = detail.now == pending
                    val pendingInvalid = detail.proxies.isNotEmpty() &&
                        detail.proxies.none { it.name == pending }
                    if (pendingApplied || pendingInvalid) {
                        removeKeys.add(key)
                    }
                }
                for (k in removeKeys) {
                    pendingProxySelections.remove(k)
                }
            }
        }
        sheetDetailsRefresher?.invoke()
        active ?: return
        val i = profiles.indexOfFirst { it.uuid == active }
        if (i >= 0) {
            notifyItemChanged(i)
        }
    }

    /**
     * Update a single proxy's delay inside the active profile's live group
     * map. Receives one push per proxy from the per-proxy health-check path
     * — bypasses the full setProxyDetails merge so we don't trigger pending /
     * disk-overlay reconciliation on every URLTest tick.
     *
     * No-ops if the proxy or group isn't tracked yet (the live engine map
     * hasn't been populated for this group, or the active profile changed
     * between the request and the callback). The caller is responsible for
     * the eventual full refresh that fills in `now` / `alive` fields the
     * per-proxy push doesn't carry.
     */
    fun patchSingleProxyDelay(groupName: String, proxyName: String, delayMs: Int) {
        if (groupName.isBlank() || proxyName.isBlank()) return
        // A single-node test resolves here too: drop its "…" even when the value did not change,
        // and only THEN re-resolve the sheet row, so the patcher reads the fresh delay.
        val wasPending = pendingNodePings.remove(proxyName)
        val changed = applyProxyDelayPatch(groupName, proxyName, delayMs)
        if (changed || wasPending) scheduleActiveCardNotify()
        // Sheet rows are refreshed by the ping-all tick during a group test; the direct patch is
        // only for the single-node flow, otherwise a 200-node burst would run 200 DiffUtil passes.
        if (wasPending) sheetDelayPatcher?.invoke(proxyName)
    }

    /** @return true when [proxyName]'s delay inside [groupName] actually changed. */
    private fun applyProxyDelayPatch(groupName: String, proxyName: String, delayMs: Int): Boolean {
        val active = activeProfileUuid ?: return false
        val current = proxyDetails
        val key = if (current.containsKey(groupName)) {
            groupName
        } else {
            // A write path: patching the wrong group's selection is worse than not patching.
            current.keys.filter { groupsMatchKey(groupName, it) }.singleOrNull() ?: return false
        }
        val existing = current[key] ?: return false
        val proxyIdx = existing.proxies.indexOfFirst { it.name == proxyName }
        if (proxyIdx < 0) return false
        if (existing.proxies[proxyIdx].delay == delayMs) return false
        val updatedProxies = existing.proxies.toMutableList().also { list ->
            list[proxyIdx] = list[proxyIdx].copy(delay = delayMs)
        }
        proxyDetails = current.toMutableMap().apply {
            this[key] = existing.copy(proxies = updatedProxies)
        }
        // The caller coalesces the rebind: a URL-test fires this once per proxy in a burst; one
        // debounced notify keeps the latest delays without freezing the main thread on big lists.
        return true
    }

    fun clearProxyDetails() {
        if (proxyDetails.isEmpty()) return
        proxyDetails = emptyMap()
        // Only the active card reads proxyDetails — clearing it cannot change
        // anything on the other rows. If we don't know who the active profile
        // is right now (rare race during VPN off→on), no card depends on the
        // stale details either; skipping the rebind here avoids the same
        // scroll-jank pattern setProxyContext used to cause.
        val uuid = activeProfileUuid ?: return
        val i = profiles.indexOfFirst { it.uuid == uuid }
        if (i >= 0) notifyItemChanged(i)
    }

    fun setPendingProxySelection(uuid: UUID, groupName: String, proxyName: String) {
        if (groupName.isBlank() || proxyName.isBlank()) return
        val key = proxySelectionKey(uuid, groupName)
        val alreadyPending = pendingProxySelections[key] == proxyName
        pendingProxySelections[key] = proxyName
        // Also mirror the choice into in-memory offline cache. Without this, after pending
        // is cleared by setProxyDetails the desiredUiSelection() falls back to a stale
        // offline value (the prior on-disk pick), causing the row highlight to bounce back.
        val perProfile = (cachedOfflineSelectionsByProfile[uuid]?.toMutableMap() ?: mutableMapOf())
        perProfile[groupName] = proxyName
        cachedOfflineSelectionsByProfile[uuid] = perProfile.toMap()
        offlineSelectionsByProfile = cachedOfflineSelectionsByProfile.toMap()
        if (alreadyPending) return
        val i = profiles.indexOfFirst { it.uuid == uuid }
        if (i >= 0) notifyItemChanged(i)
    }

    fun clearStandalonePingDelays(uuid: UUID) {
        val prefix = "${uuid}|"
        if (standalonePingDelays.keys.none { it.startsWith(prefix) }) return
        standalonePingDelays.keys.removeAll { it.startsWith(prefix) }
        val i = profiles.indexOfFirst { it.uuid == uuid }
        if (i >= 0) notifyItemChanged(i)
    }

    fun setStandalonePingResults(uuid: UUID, results: Map<String, Int>) {
        var changed = false
        for ((name, ms) in results) {
            val key = "${uuid}|$name"
            if (standalonePingDelays[key] != ms) {
                standalonePingDelays[key] = ms
                changed = true
            }
        }
        // After the write, so a pending single-node row re-resolves to the fresh value.
        for (name in results.keys) {
            if (completeNodePing(name)) changed = true
        }
        if (!changed) return
        val i = profiles.indexOfFirst { it.uuid == uuid }
        if (i >= 0) notifyItemChanged(i)
    }

    fun setExpandedUuids(uuids: Set<UUID>) {
        if (expandedUuids == uuids) return
        val old = expandedUuids
        expandedUuids = uuids
        for (i in profiles.indices) {
            val id = profiles[i].uuid
            if ((id in old) != (id in uuids)) {
                notifyItemChanged(i)
            }
        }
    }

    fun hasProxyGroupsFor(profile: Profile): Boolean =
        effectiveGroupsForProfile(profile).isNotEmpty()

    /**
     * Active node's display name (with its flag prefix) for the Home "Node" row (redesign 1.0),
     * or null when nothing is selected / groups aren't loaded yet. Same source the profile card
     * uses, so the Node row shows the flag + node exactly like the picker.
     */
    fun activeNodeDisplayName(profile: Profile): String? =
        resolveCurrentNodeDisplayName(profile)?.takeIf { it.isNotBlank() }

    /**
     * The proxy group whose current selection the Home "Node" summary displays for [profile].
     * Callers (MainActivity) use this to prime that one group's live detail on connect so the row
     * isn't blank until the user opens the picker.
     */
    fun summaryGroupForProfile(profile: Profile): String? = selectedGroupForSummary(profile)

    /** True when live engine detail (carrying `now`) is already cached for [groupName]. */
    fun hasLiveDetailForGroup(groupName: String): Boolean =
        proxyDetails[groupName] != null || proxyDetails.keys.any { groupsMatchKey(groupName, it) }

    fun showProxySheet(context: Context, profile: Profile) {
        openProxySheet(context, profile)
    }

    private fun bindExpiryChip(holder: ProfileHolder, profile: Profile, context: Context) {
        val view = holder.binding.expiryChip
        if (profile.expire <= 0L || !profile.imported || profile.pending) {
            view.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val diff = profile.expire - now
        val expired = diff <= 0L
        val totalHours = if (expired) 0L else diff / 3_600_000L
        val days = totalHours / 24L
        val hours = totalHours % 24L
        val critical = expired || days < 3L
        if (!critical) {
            view.visibility = View.GONE
            return
        }
        view.text = when {
            expired -> context.getString(R.string.proxy_sheet_expiry_expired)
            days > 0 -> context.getString(R.string.proxy_sheet_expiry_days_hours, days.toInt(), hours.toInt())
            else -> context.getString(R.string.proxy_sheet_expiry_hours, hours.toInt().coerceAtLeast(1))
        }
        view.visibility = View.VISIBLE
        view.setBackgroundResource(R.drawable.bg_m3_expiry_chip_warning)
        view.setTextColor(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnErrorContainer),
        )
        // Operator may supply X-Brand-Renew-URL; when set, critical-expiry chip
        // becomes the user's primary "renew this" action. When absent we leave
        // the chip with its layout defaults (non-clickable TextView) untouched.
        val renew = brandManifest.renewUrl?.takeIf { it.isNotBlank() }
        if (renew != null) {
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { onOpenBrandUrl?.invoke(renew) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        if (compactSubscriptions) {
            return SubscriptionHolder(
                AdapterSubscriptionBinding.inflate(parent.context.layoutInflater, parent, false),
            )
        }
        return ProfileHolder(
            AdapterProfileBinding.inflate(parent.context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = profiles[position]
        if (holder is SubscriptionHolder) {
            bindSubscriptionCard(holder, current)
            return
        }
        holder as ProfileHolder
        val binding = holder.binding
        val context = binding.root.context

        binding.profile = current
        binding.setClicked {
            if (expandOnProfileClick && current.imported) {
                onExpandToggle(current)
            } else {
                onClicked(current)
            }
        }
        binding.menuView.setOnClickListener { v ->
            onMenuClicked(current, v)
        }
        val compactHomeCard = expandOnProfileClick
        binding.rootView.minimumHeight = if (compactHomeCard) context.dp(68) else context.dp(78)
        binding.menuView.visibility = if (compactHomeCard) View.GONE else View.VISIBLE
        binding.menuView.isClickable = !compactHomeCard
        binding.menuView.isFocusable = !compactHomeCard
        val hasSupport = !announcementSupportUrl.isNullOrBlank() && current.imported && current.uuid == activeProfileUuid
        binding.supportSlot.visibility = if (!compactHomeCard && hasSupport) View.VISIBLE else View.GONE
        binding.supportView.setOnClickListener {
            announcementSupportUrl?.let { url ->
                announcementOnOpenUrl?.invoke(url) ?: announcementOnSupport?.invoke()
            }
        }
        applyActiveVisuals(holder, current)
        bindUsageAndProgress(holder, current)
        bindExpiryChip(holder, current, context)
        if (compactHomeCard) {
            binding.usageSummary.visibility = View.GONE
            binding.usageProgress.visibility = View.GONE
        }

        val selectedGroup = selectedGroupForSummary(current)
        val selectionSummary = selectedGroup?.let { group ->
            if (compactHomeCard) {
                formatSelectionSummaryForHome(group)
            } else {
                formatSelectionSummaryForProfiles(context, current, group)
            }
        }
        if (compactHomeCard) {
            val profileTitle = resolveCurrentNodeDisplayName(current)
                ?: context.getString(R.string.not_selected)
            val flag = FlagParser.parse(
                profileTitle.takeUnless { it == context.getString(R.string.not_selected) },
            )
            val titleText = if (flag != null) {
                profileTitle.removePrefix(flag.emoji)
                    .trimStart(' ', '|', '-', '_', '.', ':')
                    .ifBlank { profileTitle }
            } else {
                formatNodeHeadline(profileTitle, context).first
            }
            val groupSuffix = selectedGroup
                ?.takeIf { resolveCurrentNodeDisplayName(current) != null }
                ?.let { " · " + displayGroupName(it) }
                .orEmpty()
            binding.profileTitle.text = titleText + groupSuffix
            bindProfileFlag(binding, context, flag)
        } else {
            binding.profileTitle.text = current.name
            binding.profileFlagCard.visibility = View.GONE
            binding.profileFlagImage.visibility = View.GONE
            binding.profileFlag.visibility = View.GONE
            binding.profileIconEmoji.visibility = View.VISIBLE
            binding.profileIcon.visibility = View.GONE
            binding.profileIconEmoji.text = profileEmoji(current)
        }

        binding.serverSelectionSummary.visibility = when {
            compactHomeCard -> View.GONE
            !selectionSummary.isNullOrBlank() -> View.VISIBLE
            else -> View.GONE
        }
        binding.serverSelectionSummary.text = selectionSummary.orEmpty()
        if (useEngineFor(current) && selectedGroup != null) {
            reportVisibleGroup(current, selectedGroup)
        }

        val showForceUpdate = !compactHomeCard && current.imported && current.type != Profile.Type.File
        binding.forceUpdateSlot.visibility = if (showForceUpdate) View.VISIBLE else View.GONE
        binding.forceUpdateView.visibility = if (showForceUpdate) View.VISIBLE else View.INVISIBLE
        binding.forceUpdateView.isClickable = false
        binding.forceUpdateView.isFocusable = false
        binding.forceUpdateSlot.isClickable = showForceUpdate
        binding.forceUpdateSlot.setOnClickListener {
            if (showForceUpdate) onForceUpdate(current)
        }

    }

    private fun bindSubscriptionCard(holder: SubscriptionHolder, profile: Profile) {
        val binding = holder.binding
        val context = binding.root.context
        bindSubscriptionVisuals(binding, profile)
        binding.subscriptionChooseServer.isEnabled = profile.imported && !profile.pending
        binding.subscriptionChooseServer.setOnClickListener { showProxySheet(context, profile) }
        binding.subscriptionCard.setOnClickListener { onClicked(profile) }
        binding.subscriptionMenu.setOnClickListener {
            if (subscriptionClickGuard.accept(profile.uuid to it.id)) onMenuClicked(profile, it)
        }
        binding.subscriptionName.text = profile.name
        binding.subscriptionStatus.visibility = if (profile.active) View.VISIBLE else View.GONE
        binding.subscriptionUsage.text = if (profile.imported && !profile.pending) {
            formatUsageLine(binding.root.context, profile)
        } else {
            context.getString(R.string.subscription_not_ready)
        }
        val hasLimit = profile.imported && !profile.pending && profile.total >= 2L
        binding.subscriptionUsageProgress.visibility = if (hasLimit) View.VISIBLE else View.GONE
        binding.subscriptionUsageProgress.progress =
            (((profile.upload + profile.download).toDouble() / profile.total.coerceAtLeast(1L))
                .coerceIn(0.0, 1.0) * 1000).toInt()
        val expiry = formatExpiryLeft(profile.expire, context)
        binding.subscriptionExpiry.text = expiry.orEmpty()
        binding.subscriptionExpiry.visibility = if (expiry == null) View.GONE else View.VISIBLE
        binding.subscriptionExpiry.setTextColor(MaterialColors.getColor(
            binding.root,
            if (profile.expire > 0L && profile.expire <= System.currentTimeMillis()) {
                com.google.android.material.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            },
        ))
        // "Updated 2 hours ago" for fetched subscriptions; hidden for files and unsaved drafts.
        val showUpdated = profile.type == Profile.Type.Url && profile.imported && !profile.pending &&
            profile.updatedAt > 0L
        if (showUpdated) {
            val elapsed = (System.currentTimeMillis() - profile.updatedAt).coerceAtLeast(0L)
            binding.subscriptionUpdated.text = if (elapsed < TimeUnit.MINUTES.toMillis(1)) {
                context.getString(R.string.profile_updated_just_now)
            } else {
                context.getString(R.string.profile_updated_fmt, elapsed.elapsedIntervalString(context))
            }
        }
        binding.subscriptionUpdated.visibility = if (showUpdated) View.VISIBLE else View.GONE
        val updating = states.isUpdating(profile.uuid)
        val failed = states.hasUpdateError(profile.uuid)
        binding.subscriptionUpdateStatus.visibility = if (updating || failed) View.VISIBLE else View.GONE
        binding.subscriptionUpdateStatus.setText(
            if (updating) R.string.subscription_updating else R.string.subscription_update_error,
        )
        binding.subscriptionUpdateStatus.setTextColor(MaterialColors.getColor(
            binding.root,
            if (failed) com.google.android.material.R.attr.colorError
            else com.google.android.material.R.attr.colorOnSurfaceVariant,
        ))
        binding.subscriptionRetry.visibility = if (failed && !updating) View.VISIBLE else View.GONE
        binding.subscriptionRetry.setOnClickListener { onForceUpdate(profile) }
    }

    internal fun reportVisibleGroup(profile: Profile, groupName: String, force: Boolean = false) {
        if (!useEngineFor(profile)) return
        if (!force && lastReportedVisibleGroup[profile.uuid] == groupName) return
        lastReportedVisibleGroup[profile.uuid] = groupName
        onVisibleGroupChanged(profile, groupName)
    }

    fun formatUsageLine(context: Context, p: Profile): String =
        formatProfileUsageLine(context, p)

    override fun getItemCount(): Int = profiles.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        mainHandler.removeCallbacks(activeCardNotifyRunnable)
    }

    private companion object {
        /** Longer than any provider health-check timeout we honour; only guards a lost callback. */
        private const val NODE_PING_PENDING_TIMEOUT_MS = 20_000L

        /** Coalesce window for live URL-test delay pushes (one card rebind instead of N). */
        const val ACTIVE_CARD_NOTIFY_DEBOUNCE_MS = 250L
    }
}

