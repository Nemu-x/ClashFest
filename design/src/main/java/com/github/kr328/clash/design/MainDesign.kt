package com.github.kr328.clash.design

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.R as MaterialR
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.main.MainPager
import com.github.kr328.clash.design.main.PowerVisuals
import com.github.kr328.clash.design.main.SubscriptionDetailsSheet
import com.github.kr328.clash.design.main.bindAnnouncementCard
import com.github.kr328.clash.design.main.bindMainNodeFlag
import com.github.kr328.clash.design.main.bindOperatorPage
import com.github.kr328.clash.design.main.profileUsageLabel
import com.github.kr328.clash.design.main.profilesTabTouchHelper
import com.github.kr328.clash.design.main.showAboutSheet
import com.github.kr328.clash.design.main.showBrandDevDialog
import com.github.kr328.clash.design.main.showProfilesTabSortMenu
import com.github.kr328.clash.design.main.sortProfilesForTab
import com.github.kr328.clash.design.databinding.BottomSheetMainModeBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.HomeBackgroundStyle
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.ClickGuard
import com.github.kr328.clash.design.util.hostActivity
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.model.ProxyTransportInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.UUID

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenNewProfile,
        OpenSettings,
        OpenThemeSettings,
        OpenLanguageSettings,
        OpenAppSettings,
        OpenAbout,
        PatchModeDirect,
        PatchModeGlobal,
        PatchModeRule,
        OpenImportClipboard,
        OpenImportQr,
        CycleTheme,
        MainTabChanged,
        OpenLogs,
        OpenConnections,
        /** Routing rules list screen. */
        OpenRouting,
        RefreshRouting,
        OpenNewRule,
        OpenEffectiveRules,
        /** Rule snippets / editor screen. */
        OpenRules,
        /** Proxy chain screen. */
        OpenProxyChain,
        /** Direct entry to per-app routing for quick access from the routing tab. */
        OpenPerAppRouting,
        /** Subscriptions / profiles list. */
        OpenProfiles,
        /** Companion remote-control hub (quick entry surfaced on TV home). */
        OpenCompanion,
    }

    /** Public: MainActivity captures/restores the active tab across a soft recreate. */
    enum class MainTab {
        Home,
        Profiles,
        Routing,
        Operator,
        Settings,
    }

    /** Set by MainActivity to react to taps on the in-header update badge. */
    var onUpdateBadgeTap: (() -> Unit)? = null

    val profileActivateRequests = Channel<Profile>(Channel.UNLIMITED)
    val profileMenuRequests = Channel<Pair<Profile, View>>(Channel.UNLIMITED)
    val profileEditRequests = Channel<Profile>(Channel.UNLIMITED)
    val patchHomeProxyRequests = Channel<Triple<Profile, String, String>>(Channel.CONFLATED)
    /**
     * A ping-all request. Was a Triple until the custom latency target arrived; [testUrl] is blank
     * for a normal tap and carries a one-shot host when the user long-pressed and typed one.
     */
    data class PingAllRequest(
        val profile: Profile,
        val group: String,
        val proxyNames: List<String>,
        val testUrl: String = "",
    )

    val profilePingAllRequests = Channel<PingAllRequest>(Channel.UNLIMITED)
    /** Single-node latency test (tap on a capsule): profile, group, proxy. */
    val proxyPingNodeRequests = Channel<Triple<Profile, String, String>>(Channel.UNLIMITED)
    val profileForceUpdateRequests = Channel<Profile>(Channel.UNLIMITED)
    val profileProxyYamlRequests = Channel<Triple<Profile, String, String>>(Channel.UNLIMITED)
    /** Fires when user expands/collapses any profile panel so the host can reload proxy previews. */
    val profileExpandChanged = Channel<Unit>(Channel.CONFLATED)
    val profileVisibleGroupChanged = Channel<Pair<Profile, String>>(Channel.CONFLATED)
    /** Profiles-tab manager: drag-reorder committed + update-all tapped. */
    val profileReorderRequests = Channel<List<Profile>>(Channel.UNLIMITED)
    val profileUpdateAllRequests = Channel<Unit>(Channel.UNLIMITED)

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private val clickGuard = ClickGuard<Request>(600L, SystemClock::elapsedRealtime)
    private val subscriptionClickGuard = ClickGuard<Pair<UUID, Int>>(600L, SystemClock::elapsedRealtime)

    private var clashRunningState: Boolean = false
    private var tunnelStartingState: Boolean = false
    private val hostLifecycle = (context.hostActivity() as? LifecycleOwner)?.lifecycle
    private var brandHolder: com.github.kr328.clash.design.branding.BrandHolder =
        com.github.kr328.clash.design.branding.BrandHolder.EMPTY
    private val uiStore = UiStore(context)
    private val powerVisuals = PowerVisuals(
        context = context,
        binding = binding,
        hostLifecycle = hostLifecycle,
        brandHolder = { brandHolder },
        isRunning = { clashRunningState },
        isTunnelStarting = { tunnelStartingState },
        isHomeVisible = { currentTab == MainTab.Home },
        isAboutOpen = { clickGuard.isOpen(Request.OpenAbout) },
    )
    private val mainPager = MainPager(
        binding = binding,
        uiStore = uiStore,
        sendRequest = { requests.trySend(it) },
        onTabRendered = { powerVisuals.refreshBreath() },
    )
    private val expandedProfileUuids: LinkedHashSet<UUID> = linkedSetOf()
    private val subscriptionPreviewUuids: LinkedHashSet<UUID> = linkedSetOf()
    private val pendingSubscriptionPickers = mutableSetOf<UUID>()
    private var currentModeSegment: TunnelState.Mode = TunnelState.Mode.Rule
    private var suppressModeSegment = false
    private var activeProfileForQuickActions: Profile? = null
    private var activeAnnouncementSupportUrl: String? = null
    private var activeAnnouncementOnOpenUrl: ((String) -> Unit)? = null
    private var activeAnnouncementOnSupport: (() -> Unit)? = null

    private val profileAdapter: ProfileAdapter = ProfileAdapter(
        { profile -> profileActivateRequests.trySend(profile) },
        { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
        { profile -> toggleProfileExpand(profile) },
        { profile, group, proxyName ->
            patchHomeProxyRequests.trySend(Triple(profile, group, proxyName))
        },
        { profile, group, proxyNames, testUrl ->
            profilePingAllRequests.trySend(PingAllRequest(profile, group, proxyNames, testUrl))
        },
        { profile -> profileForceUpdateRequests.trySend(profile) },
        { profile, group, proxy -> profileProxyYamlRequests.trySend(Triple(profile, group, proxy)) },
        { profile, group -> profileVisibleGroupChanged.trySend(profile to group) },
        { profile, group, proxy -> proxyPingNodeRequests.trySend(Triple(profile, group, proxy)) },
        expandOnProfileClick = true,
        readProxyGroupLayout = { uiStore.proxyGroupLayout },
        writeProxyGroupLayout = { uiStore.proxyGroupLayout = it },
    )

    /** Subscription cards open details; activation is an explicit action inside the sheet. */
    private val tabProfileAdapter: ProfileAdapter = ProfileAdapter(
        { profile -> showSubscriptionDetails(profile) },
        { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
        { profile -> openSubscriptionPicker(profile) },
        { profile, group, proxyName ->
            patchHomeProxyRequests.trySend(Triple(profile, group, proxyName))
        },
        { profile, group, proxyNames, testUrl ->
            profilePingAllRequests.trySend(PingAllRequest(profile, group, proxyNames, testUrl))
        },
        { profile ->
            requestSubscriptionUpdate(profile)
        },
        { profile, group, proxy -> profileProxyYamlRequests.trySend(Triple(profile, group, proxy)) },
        { profile, group -> profileVisibleGroupChanged.trySend(profile to group) },
        { profile, group, proxy -> proxyPingNodeRequests.trySend(Triple(profile, group, proxy)) },
        expandOnProfileClick = false,
        compactSubscriptions = true,
        readProxyGroupLayout = { uiStore.proxyGroupLayout },
        writeProxyGroupLayout = { uiStore.proxyGroupLayout = it },
    )
    private var tabProfilesAll: List<Profile> = emptyList()
    private val subscriptionDetails: SubscriptionDetailsSheet = SubscriptionDetailsSheet(
        context = context,
        adapter = tabProfileAdapter,
        clickGuard = subscriptionClickGuard,
        profiles = { tabProfilesAll },
        isLoadingServers = { it in pendingSubscriptionPickers },
        onOpened = { profile ->
            subscriptionPreviewUuids.clear()
            subscriptionPreviewUuids.add(profile.uuid)
            tabProfileAdapter.setExpandedUuids(subscriptionPreviewUuids.toSet())
            profileExpandChanged.trySend(Unit)
        },
        onDismissed = { uuid -> pendingSubscriptionPickers.remove(uuid) },
        onMenu = { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
        onActivate = { profile -> profileActivateRequests.trySend(profile) },
        onEdit = { profile -> profileEditRequests.trySend(profile) },
        onPickServer = { profile -> openSubscriptionPicker(profile) },
        onUpdate = { profile -> requestSubscriptionUpdate(profile) },
    )
    private val tabItemTouchHelper = profilesTabTouchHelper(uiStore, tabProfileAdapter) { order ->
        profileReorderRequests.trySend(order)
    }

    private fun sortTabProfiles(profiles: List<Profile>): List<Profile> =
        sortProfilesForTab(profiles, uiStore)

    private fun showProfilesTabSort(anchor: View) =
        showProfilesTabSortMenu(
            context = context,
            anchor = anchor,
            uiStore = uiStore,
            adapter = tabProfileAdapter,
            allProfiles = { tabProfilesAll },
            onUpdateAll = { requestAllSubscriptionUpdates() },
        )

    override val root: View
        get() = binding.root

    fun getExpandedProfileUuids(): Set<UUID> = (expandedProfileUuids + subscriptionPreviewUuids).toSet()

    /**
     * Pushes operator-pushed subscription metadata into the home UI and the active profile card.
     */
    suspend fun setAnnouncement(
        text: String?,
        url: String?,
        supportUrl: String? = null,
        sourceUuid: UUID? = null,
        sourceName: String? = null,
        onOpenUrl: ((String) -> Unit)? = null,
        onSupport: (() -> Unit)? = null,
    ) {
        withContext(Dispatchers.Main) {
            val message = text
                ?.takeIf { it.isNotBlank() }
                ?.let { com.github.kr328.clash.common.util.MaybeBase64.decode(it).trim() }
                .orEmpty()
            val announcementUrl = url?.takeIf { it.isNotBlank() }
            val support = supportUrl?.takeIf { it.isNotBlank() }
            activeAnnouncementSupportUrl = support
            activeAnnouncementOnOpenUrl = onOpenUrl
            activeAnnouncementOnSupport = onSupport

            bindAnnouncementCard(
                context = context,
                binding = binding,
                uiStore = uiStore,
                message = message,
                announcementUrl = announcementUrl,
                support = support,
                sourceUuid = sourceUuid,
                sourceName = sourceName,
                onOpenUrl = onOpenUrl,
                onSupport = onSupport,
            )

            profileAdapter.setActiveAnnouncement(
                text = text,
                url = url,
                supportUrl = supportUrl,
                onOpenUrl = onOpenUrl,
                onSupport = onSupport,
            )
            renderActiveProfileCard(activeProfileForQuickActions)
        }
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    /**
     * Apply the latest operator-brand snapshot to the main screen surfaces:
     * operator tagline, profile cards, operator page and power-button accent.
     * The main header keeps the app name and logo defined in the layout.
     */
    var onOpenBrandUrl: ((String) -> Unit)? = null
    var onOpenAbout: (() -> Unit)? = null

    suspend fun applyBrand(holder: com.github.kr328.clash.design.branding.BrandHolder) {
        withContext(Dispatchers.Main) {
            brandHolder = holder
            renderBrandHeader()
            // BrandThemeApplier installs the M3 harmonised palette plus the
            // neutral-surface overlay at Activity onCreate / after recreate.
            // Once that overlay is in the theme, every widget reading
            // ?attr/colorPrimary etc. picks up the brand automatically — no
            // programmatic walker needed. Power button is the one place
            // where we explicitly override (running state) because the
            // attrs we picked for it are intentionally state-aware in code,
            // not in XML.
            applyPowerVisuals()
            reconcileTabsForBrand()
            reconcileGlobalModeForBrand()
            renderOperatorPage()
            profileAdapter.setBrandManifest(holder.manifest) { url ->
                onOpenBrandUrl?.invoke(url)
            }
            profileAdapter.setPrimaryProxyGroup(holder.manifest.primaryProxyGroup)
            tabProfileAdapter.setPrimaryProxyGroup(holder.manifest.primaryProxyGroup)
            profileAdapter.setProxyGroupLayoutDefault(holder.manifest.proxyGroupLayout)
            tabProfileAdapter.setProxyGroupLayoutDefault(holder.manifest.proxyGroupLayout)
        }
    }

    private fun renderOperatorPage() {
        bindOperatorPage(context, binding, brandHolder, uiStore) { url -> onOpenBrandUrl?.invoke(url) }
    }

    private fun renderBrandHeader() {
        val brand = brandHolder.manifest

        // Tagline: only show / write when operator supplied one.
        val taglineView = binding.mainHeaderTagline
        val tagline = brand.tagline?.takeIf { it.isNotBlank() }
        if (tagline != null) {
            taglineView.text = tagline
            taglineView.visibility = View.VISIBLE
        } else {
            taglineView.visibility = View.GONE
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            clashRunningState = running
            binding.clashRunning = running
            if (running) {
                tunnelStartingState = false
                binding.tunnelStarting = false
            }
            applyPowerVisuals()
        }
    }

    suspend fun setTunnelStarting(starting: Boolean) {
        withContext(Dispatchers.Main) {
            tunnelStartingState = starting
            binding.tunnelStarting = starting
            applyPowerVisuals()
        }
    }

    suspend fun setPingingProfile(uuid: UUID?) {
        withContext(Dispatchers.Main) {
            profileAdapter.setPingingUuid(uuid)
            tabProfileAdapter.setPingingUuid(uuid)
        }
    }

    private fun applyPowerVisuals() = powerVisuals.applyVisuals()

    private fun updateBreathAnimator(requestedRunning: Boolean, starting: Boolean) =
        powerVisuals.updateBreathAnimator(requestedRunning, starting)

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    /** Live ↓/↑ speed under the connect hero (redesign 1.0, issue #101). */
    suspend fun setSpeed(down: String, up: String) {
        withContext(Dispatchers.Main) {
            binding.mainSpeed.text = context.getString(R.string.main_speed_fmt, down, up)
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            val normalized = normalizeMode(mode)
            currentModeSegment = normalized
            binding.mode = when (normalized) {
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                else -> context.getString(R.string.rule_mode)
            }
            // Reflect on the segmented control without re-triggering the change request.
            suppressModeSegment = true
            binding.mainModeSegment.check(
                if (normalized == TunnelState.Mode.Global) R.id.main_mode_global else R.id.main_mode_rule,
            )
            suppressModeSegment = false
        }
    }

    /**
     * Applies saved tunnel mode preference to the dashboard before the first [fetch],
     * so mode chips do not briefly show a stale default while the core state loads.
     */
    suspend fun applyInitialModeFromPreference(pref: String?) {
        val mode = when (pref) {
            TunnelState.Mode.Rule.name -> TunnelState.Mode.Rule
            TunnelState.Mode.Global.name -> TunnelState.Mode.Global
            TunnelState.Mode.Direct.name -> TunnelState.Mode.Rule
            else -> return
        }
        setMode(mode)
    }

    private fun normalizeMode(mode: TunnelState.Mode): TunnelState.Mode =
        if (mode == TunnelState.Mode.Direct) TunnelState.Mode.Rule else mode

    suspend fun syncThemeToggleIcon(mode: DarkMode) {
        withContext(Dispatchers.Main) {
            binding.btnThemeToggle.text = context.getString(
                when (mode) {
                    DarkMode.ForceLight -> R.string.main_emoji_moon
                    else -> R.string.main_emoji_sun
                },
            )
        }
    }

    suspend fun patchProfiles(profiles: List<Profile>) {
        withContext(Dispatchers.Main) {
            binding.hasProfiles = profiles.isNotEmpty()
            binding.profilesLoaded = true
            val homeProfiles = profiles.filter { it.active }.ifEmpty { profiles.take(1) }
            activeProfileForQuickActions = profiles.firstOrNull { it.active } ?: profiles.firstOrNull()
            renderActiveProfileCard(activeProfileForQuickActions)
            profileAdapter.apply {
                val ids = homeProfiles.map { it.uuid }.toSet()
                expandedProfileUuids.retainAll { it in ids }
                patchDataSet(this::profiles, homeProfiles, id = { it.uuid })
            }
            profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())

            tabProfilesAll = profiles
            tabProfileAdapter.apply {
                val ids = profiles.map { it.uuid }.toSet()
                subscriptionPreviewUuids.retainAll(ids)
                states.retainProfiles(ids)
                patchDataSet(this::profiles, sortTabProfiles(profiles), id = { it.uuid })
            }
            tabProfileAdapter.setExpandedUuids(subscriptionPreviewUuids.toSet())
            binding.profilesTabSkeleton.visibility = View.GONE
            binding.profilesTabList.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
            binding.profilesTabEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            val tabUpdatable = profiles.any { it.imported && it.type != Profile.Type.File }
            binding.profilesTabRefresh.isEnabled = tabUpdatable
            refreshSubscriptionDetails()
        }
    }

    private fun renderActiveProfileCard(profile: Profile?) {
        val p = profile
        binding.mainActiveProfileValue.text = p?.name.orEmpty().ifBlank { context.getString(R.string.not_selected) }
        binding.mainActiveProfileMeta.text = p?.let { profileUsageLabel(context, uiStore, it) }.orEmpty()
        binding.mainActiveProfileMeta.visibility = if (p != null) View.VISIBLE else View.GONE
        val showUpdate = p?.imported == true && p.type != Profile.Type.File
        binding.mainActiveProfileUpdate.visibility = if (showUpdate) View.VISIBLE else View.GONE
        val showSupport = !resolveSupportUrl().isNullOrBlank()
        binding.mainActiveProfileSupport.visibility = if (showSupport) View.VISIBLE else View.GONE
        binding.mainActiveProfileActions.visibility = if (showUpdate || showSupport) View.VISIBLE else View.GONE
        // Redesign 1.0: Node row shows the active node as a circular flag icon (like the picker) +
        // the clean name, and opens the proxy picker for the active profile (loads groups first).
        val nodeName = p?.let { profileAdapter.activeNodeDisplayName(it) }
        val nodeFlag = FlagParser.parse(nodeName)
        bindMainNodeFlag(context, binding, nodeFlag)
        val cleanName = nodeName?.let { n ->
            nodeFlag?.let { n.removePrefix(it.emoji).trimStart(' ', '|', '-', '_', '.', ':').ifBlank { n } } ?: n
        }
        binding.mainActiveNodeValue.text = cleanName ?: context.getString(R.string.main_node_choose)
        binding.mainActiveNodeRow.setOnClickListener { p?.let { openNodePicker(it) } }
    }

    private fun resolveSupportUrl(): String? =
        activeAnnouncementSupportUrl?.takeIf { it.isNotBlank() }
            ?: uiStore.supportUrl.takeIf { it.isNotBlank() }

    suspend fun patchProxyGroups(
        names: List<String>,
        running: Boolean,
        mode: TunnelState.Mode?,
        lastGroupHint: String?,
        offlinePreviewByProfile: Map<UUID, Map<String, ProxyGroupPreviewRow>> = emptyMap(),
        activeProfileUuid: UUID? = null,
        offlineSelectionsByProfile: Map<UUID, Map<String, String>> = emptyMap(),
        transportInfoByProfile: Map<UUID, Map<String, ProxyTransportInfo>> = emptyMap(),
    ) {
        withContext(Dispatchers.Main) {
            profileAdapter.setProxyContext(
                names,
                running,
                mode,
                lastGroupHint,
                offlinePreviewByProfile,
                activeProfileUuid,
                offlineSelectionsByProfile,
                transportInfoByProfile,
                // Same flag [names] was queried with (MainActivity -> queryProxyGroupNames), so the
                // offline preview hides exactly the groups the engine hides.
                uiStore.proxyExcludeNotSelectable,
            )
            profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
            tabProfileAdapter.setProxyContext(
                names,
                running,
                mode,
                lastGroupHint,
                offlinePreviewByProfile,
                activeProfileUuid,
                offlineSelectionsByProfile,
                transportInfoByProfile,
                // Same flag [names] was queried with (MainActivity -> queryProxyGroupNames), so the
                // offline preview hides exactly the groups the engine hides.
                uiStore.proxyExcludeNotSelectable,
            )
            refreshSubscriptionDetails()
        }
    }

    suspend fun patchProxyDetails(details: Map<String, ProxyGroup>) {
        withContext(Dispatchers.Main) {
            profileAdapter.setProxyDetails(details)
            tabProfileAdapter.setProxyDetails(details)
            refreshSubscriptionDetails()
        }
    }

    /** Group whose selection the Home "Node" row summarises for [profile] (for connect-time priming). */
    fun summaryGroupFor(profile: Profile): String? = profileAdapter.summaryGroupForProfile(profile)

    /** True when live engine detail for [groupName] is already cached (skip the connect-time prime). */
    fun hasLiveDetailForGroup(groupName: String): Boolean = profileAdapter.hasLiveDetailForGroup(groupName)

    suspend fun patchSingleProxyDelay(group: String, proxy: String, delayMs: Int) {
        withContext(Dispatchers.Main) {
            profileAdapter.patchSingleProxyDelay(group, proxy, delayMs)
            tabProfileAdapter.patchSingleProxyDelay(group, proxy, delayMs)
        }
    }

    /** Drops the "…" of a single-node test that produced no measurement (offline miss, engine error). */
    suspend fun clearNodePingPending(proxy: String) {
        withContext(Dispatchers.Main) {
            profileAdapter.completeNodePing(proxy)
            tabProfileAdapter.completeNodePing(proxy)
        }
    }

    suspend fun clearProxyDetails() {
        withContext(Dispatchers.Main) {
            profileAdapter.clearProxyDetails()
        }
    }

    suspend fun markProxySelectionPending(profile: Profile, group: String, proxy: String) {
        withContext(Dispatchers.Main) {
            profileAdapter.setPendingProxySelection(profile.uuid, group, proxy)
        }
    }

    suspend fun clearStandalonePingForProfile(uuid: UUID) {
        withContext(Dispatchers.Main) {
            profileAdapter.clearStandalonePingDelays(uuid)
            tabProfileAdapter.clearStandalonePingDelays(uuid)
        }
    }

    suspend fun patchStandalonePingResults(uuid: UUID, results: Map<String, Int>) {
        withContext(Dispatchers.Main) {
            profileAdapter.setStandalonePingResults(uuid, results)
            tabProfileAdapter.setStandalonePingResults(uuid, results)
        }
    }

    private fun toggleProfileExpand(profile: Profile) {
        if (!profile.imported) {
            return
        }
        val hadGroups = profileAdapter.hasProxyGroupsFor(profile)
        val needsRefresh = expandedProfileUuids.add(profile.uuid)
        profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
        if (needsRefresh || !hadGroups) {
            profileExpandChanged.trySend(Unit)
        }
        openProxySheetWhenReady(profile)
    }

    /** Load this subscription's groups before opening the searchable server picker. */
    private fun openSubscriptionPicker(profile: Profile, attempt: Int = 0) {
        if (!profile.imported) return
        if (attempt > 0 && profile.uuid !in pendingSubscriptionPickers) return
        if (attempt == 0) {
            if (!pendingSubscriptionPickers.add(profile.uuid)) return
            subscriptionPreviewUuids.add(profile.uuid)
            tabProfileAdapter.setExpandedUuids(subscriptionPreviewUuids.toSet())
            profileExpandChanged.trySend(Unit)
            refreshSubscriptionDetails()
        }
        val current = tabProfilesAll.firstOrNull { it.uuid == profile.uuid }
        if (current == null) {
            pendingSubscriptionPickers.remove(profile.uuid)
            return
        }
        if (tabProfileAdapter.hasProxyGroupsFor(current) || attempt >= 6) {
            pendingSubscriptionPickers.remove(profile.uuid)
            refreshSubscriptionDetails()
            tabProfileAdapter.showProxySheet(context, current)
        } else {
            binding.profilesTabList.postDelayed({ openSubscriptionPicker(current, attempt + 1) }, 400L)
        }
    }

    private fun showSubscriptionDetails(profile: Profile) = subscriptionDetails.show(profile)

    private fun refreshSubscriptionDetails() = subscriptionDetails.refresh()

    fun setSubscriptionActivating(uuid: UUID?) = subscriptionDetails.setActivating(uuid)

    private fun requestSubscriptionUpdate(profile: Profile) {
        if (!profile.imported || profile.type == Profile.Type.File) return
        if (!tabProfileAdapter.states.beginUpdate(profile.uuid)) return
        refreshSubscriptionUpdateUi(profile.uuid)
        profileForceUpdateRequests.trySend(profile)
    }

    fun markSubscriptionUpdateStarted(uuid: UUID): Boolean {
        if (!tabProfileAdapter.states.beginUpdate(uuid)) return false
        refreshSubscriptionUpdateUi(uuid)
        return true
    }

    fun finishSubscriptionUpdate(uuid: UUID, success: Boolean) {
        tabProfileAdapter.states.finishUpdate(uuid, success)
        refreshSubscriptionUpdateUi(uuid)
    }

    fun recordSubscriptionUpdateResult(uuid: UUID, success: Boolean) {
        tabProfileAdapter.states.recordObservedUpdate(uuid, success)
        refreshSubscriptionUpdateUi(uuid)
    }

    private fun refreshSubscriptionUpdateUi(uuid: UUID) {
        val index = tabProfileAdapter.profiles.indexOfFirst { it.uuid == uuid }
        if (index >= 0) tabProfileAdapter.notifyItemChanged(index)
        refreshSubscriptionDetails()
    }

    private fun requestAllSubscriptionUpdates() {
        if (tabProfileAdapter.states.allUpdating) return
        tabProfileAdapter.states.allUpdating = true
        binding.profilesTabRefresh.isRefreshing = true
        profileUpdateAllRequests.trySend(Unit)
    }

    fun finishAllSubscriptionUpdates() {
        tabProfileAdapter.states.allUpdating = false
        binding.profilesTabRefresh.isRefreshing = false
    }

    /**
     * Node-row entry to the proxy picker (redesign 1.0). Unlike a bare [openProxySheetWhenReady],
     * this first ensures the profile's proxy groups are loaded (the same load `toggleProfileExpand`
     * triggers) so the sheet isn't empty; then opens it. Does not toggle the card's expand state.
     */
    /**
     * Notification "Change node" action: open the Home node picker for the active profile.
     * @return false when no active profile is known yet (cold start), so the caller can retry.
     */
    fun openNodePickerForActiveProfile(): Boolean {
        val profile = activeProfileForQuickActions?.takeIf { it.imported } ?: return false
        selectTab(MainTab.Home)
        openNodePicker(profile)
        return true
    }

    private fun openNodePicker(profile: Profile) {
        if (!profile.imported) return
        if (!profileAdapter.hasProxyGroupsFor(profile)) {
            expandedProfileUuids.add(profile.uuid)
            profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
            profileExpandChanged.trySend(Unit)
        }
        openProxySheetWhenReady(profile)
    }

    private fun openProxySheetWhenReady(profile: Profile, attempt: Int = 0) {
        // Was 180ms x 15 ≈ 2.7s of recursive postDelayed wakeups while waiting for
        // proxy groups to load. 400ms x 6 ≈ 2.4s but with ~2.5x fewer wakeups.
        if (profileAdapter.hasProxyGroupsFor(profile) || attempt >= 6) {
            profileAdapter.showProxySheet(context, profile)
            return
        }
        binding.profileList.postDelayed({
            openProxySheetWhenReady(profile, attempt + 1)
        }, 400L)
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                profileEditRequests.trySend(profile)
            }
        }
    }

    fun updateElapsed() {
        profileAdapter.updateElapsed()
    }

    suspend fun showAbout(
        versionName: String,
        coreVersion: String,
        initialUpdateStatus: String? = null,
        onCheckUpdates: (((Boolean) -> Unit, (String?) -> Unit) -> Unit)? = null,
    ) {
        withContext(Dispatchers.Main) {
            showAboutSheet(
                context = context,
                uiStore = uiStore,
                brandHolder = brandHolder,
                versionName = versionName,
                coreVersion = coreVersion,
                initialUpdateStatus = initialUpdateStatus,
                onCheckUpdates = onCheckUpdates,
                isOpen = { clickGuard.isOpen(Request.OpenAbout) },
                tryOpen = { clickGuard.tryOpen(Request.OpenAbout) },
                onClosed = {
                    clickGuard.close(Request.OpenAbout)
                    applyPowerVisuals()
                },
                onBeforeShow = { updateBreathAnimator(false, false) },
                onDevMenu = { showBrandDevDialog(context) { profileExpandChanged.trySend(Unit) } },
            )
        }
    }

    private fun showModeSheet() {
        val sheet = BottomSheetMainModeBinding.inflate(context.layoutInflater)
        val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
        val isGlobal = currentModeSegment == TunnelState.Mode.Global

        sheet.modeRuleSelected.visibility = if (isGlobal) View.INVISIBLE else View.VISIBLE
        sheet.modeGlobalSelected.visibility = if (isGlobal) View.VISIBLE else View.INVISIBLE
        sheet.modeRuleRow.setOnClickListener {
            requests.trySend(Request.PatchModeRule)
            dialog.dismiss()
        }
        sheet.modeGlobalRow.setOnClickListener {
            requests.trySend(Request.PatchModeGlobal)
            dialog.dismiss()
        }

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun reconcileTabsForBrand() = mainPager.reconcileTabs(brandHolder)

    /**
     * Operator POLICY (works WITHOUT X-Branding-Enabled): when X-Brand-Hide-Global-Mode is set, hide
     * the Home Rule/Global mode toggle entirely and pin the app to Rule. Read straight off the
     * manifest — deliberately NOT gated by [brandHolder]'s isActive — so an operator can restrict
     * Global mode without opting into the full visual brand. Every other X-Brand-* field still
     * requires branding to be explicitly enabled.
     */
    private fun reconcileGlobalModeForBrand() {
        val hideGlobal = brandHolder.manifest.hideGlobalMode == true
        // Hide only the Global segment (not the whole Mode row) so "Mode: Rule" still reads clearly
        // and the card outline stays intact. The toggle group re-rounds the lone Rule button.
        binding.mainModeGlobal.visibility = if (hideGlobal) View.GONE else View.VISIBLE
        if (hideGlobal && currentModeSegment == TunnelState.Mode.Global) {
            // Operator forbids Global — flip back to Rule, mirroring a user tap on the Rule segment.
            requests.trySend(Request.PatchModeRule)
        }
    }

    /**
     * Toggle the in-header update indicator. MainActivity polls
     * [AppUpdateChecker.isUpdateAvailable] from onResume and from background
     * opportunistic checks, then calls this to reflect the result.
     */
    fun setUpdateBadgeVisible(visible: Boolean) {
        binding.mainHeaderUpdateBadge.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Switch to the Profiles tab (the in-app profile manager) instead of opening a separate screen. */
    fun openProfilesTab() = mainPager.selectMainTab(MainTab.Profiles)

    /** The logical tab currently shown — captured by MainActivity before a soft recreate (D5). */
    val currentTab: MainTab?
        get() = mainPager.currentTab

    /**
     * Non-animated tab restore after a soft recreate (see [MainPager.selectTab]); no-op if [tab]
     * isn't in the current tab set.
     */
    fun selectTab(tab: MainTab) = mainPager.selectTab(tab)

    fun patchRoutingSummary(profile: String?, state: com.github.kr328.clash.service.model.RuleState?, error: Boolean = false) {
        val loading = profile != null && state == null && !error
        binding.mainRoutingLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.mainRoutingCounts.visibility = if (loading) View.GONE else View.VISIBLE
        binding.mainRoutingCounts.isClickable = error
        binding.mainRoutingCounts.isFocusable = error
        binding.mainRoutingCounts.setOnClickListener { requests.trySend(Request.RefreshRouting) }
        binding.mainRoutingProfile.text = profile ?: context.getString(R.string.routing_no_subscription)
        binding.mainRoutingAdd.isEnabled = profile != null && state != null
        binding.mainRoutingCounts.text = when {
            error -> context.getString(R.string.routing_load_error)
            profile == null -> context.getString(R.string.routing_no_subscription_hint)
            state == null -> context.getString(R.string.routing_open_to_load)
            else -> context.getString(R.string.routing_counts_fmt,
                state.rules.count { it.enabled && !it.deleted && it.source == com.github.kr328.clash.service.model.RuleSource.MANUAL },
                state.rules.count { it.enabled && !it.deleted && it.source == com.github.kr328.clash.service.model.RuleSource.PROVIDER },
                state.providers.count { it.enabled })
        }
    }

    init {
        binding.self = this
        binding.tunnelStarting = false
        binding.hasProfiles = false
        binding.profilesLoaded = false
        applyHomeBackgroundStyle()
        mainPager.setup()

        // Surface the remote-control quick entry on the TV home (deep settings nav with a D-pad
        // is painful); on phones it lives in the "+" sheet instead. Shown in both the empty and
        // active home states so a fresh TV can enable it to receive its first subscription.
        if (context.isTelevision()) {
            binding.mainCompanionRow.visibility = View.VISIBLE
            binding.mainCompanionEmptyButton.visibility = View.VISIBLE
        }

        binding.profileList.also {
            it.applyLinearAdapter(context, profileAdapter)
            (it.layoutManager as? LinearLayoutManager)?.stackFromEnd = false
            it.setHasFixedSize(true)
            // Change-animations were running on every traffic tick across all visible cards
            // (~2–8s cadence) and added measurable GPU/CPU load. Add/remove animations are kept,
            // but per-item rebind animations are disabled.
            it.itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                changeDuration = 0
                moveDuration = 0
            }
        }

        binding.profilesTabList.also {
            it.applyLinearAdapter(context, tabProfileAdapter)
            it.itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                changeDuration = 0
                moveDuration = 0
            }
        }
        tabItemTouchHelper.attachToRecyclerView(binding.profilesTabList)
        binding.profilesTabSort.setOnClickListener { showProfilesTabSort(it) }
        binding.profilesTabRefresh.setOnChildScrollUpCallback { _, _ -> binding.profilesTabList.canScrollVertically(-1) }
        binding.profilesTabRefresh.setColorSchemeColors(context.resolveThemedColor(MaterialR.attr.colorPrimary))
        binding.profilesTabRefresh.setOnRefreshListener { requestAllSubscriptionUpdates() }

        powerVisuals.install()
        binding.mainPowerCard.setOnClickListener {
            request(Request.ToggleStatus)
        }

        binding.tunnelStarting = false
        applyPowerVisuals()

        binding.mainNavHome.setOnClickListener { mainPager.selectMainTab(MainTab.Home) }
        binding.mainNavProfiles.setOnClickListener { mainPager.selectMainTab(MainTab.Profiles) }
        binding.mainNavRouting.setOnClickListener { mainPager.selectMainTab(MainTab.Routing) }
        binding.mainNavOperator.setOnClickListener { mainPager.selectMainTab(MainTab.Operator) }
        binding.mainNavSettings.setOnClickListener { mainPager.selectMainTab(MainTab.Settings) }
        binding.mainHeaderUpdateBadge.setOnClickListener { onUpdateBadgeTap?.invoke() }
        // Redesign 1.0: Mode is an in-place segmented control (Rule/Global) instead of a row+sheet.
        binding.mainModeSegment.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressModeSegment) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.main_mode_rule -> requests.trySend(Request.PatchModeRule)
                R.id.main_mode_global -> requests.trySend(Request.PatchModeGlobal)
            }
        }
        binding.mainActiveProfileUpdate.setOnClickListener {
            activeProfileForQuickActions?.let { profile ->
                requestSubscriptionUpdate(profile)
            }
        }
        binding.mainActiveProfileSupport.setOnClickListener {
            val target = resolveSupportUrl() ?: return@setOnClickListener
            activeAnnouncementOnOpenUrl?.invoke(target) ?: activeAnnouncementOnSupport?.invoke()
        }
    }

    private fun applyHomeBackgroundStyle() {
        when (uiStore.homeBackgroundStyle) {
            HomeBackgroundStyle.MaterialYou -> binding.root.setBackgroundColor(
                context.resolveThemedColor(com.google.android.material.R.attr.colorSurface),
            )
            HomeBackgroundStyle.Preview -> binding.root.setBackgroundResource(
                context.resolveThemedResourceId(R.attr.mainDashboardBackground),
            )
            HomeBackgroundStyle.Sloth -> binding.root.setBackgroundResource(
                context.resolveThemedResourceId(R.attr.mainDashboardBackground),
            )
        }
    }

    fun request(request: Request) {
        when (request) {
            Request.PatchModeDirect, Request.PatchModeGlobal, Request.PatchModeRule,
            Request.CycleTheme -> Unit
            else -> if (!clickGuard.accept(request)) return
        }
        if (request == Request.OpenAbout && onOpenAbout != null) {
            onOpenAbout?.invoke()
            return
        }
        requests.trySend(request)
    }
}
