package com.github.kr328.clash.design

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.HomeBackgroundStyle
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.ClickGuard
import com.github.kr328.clash.design.util.hostActivity
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
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
        OpenAppSettings,
        OpenAbout,
        PatchModeDirect,
        PatchModeGlobal,
        PatchModeRule,
        OpenImportClipboard,
        OpenImportQr,
        CycleTheme,
        OpenLogs,
        OpenConnections,
        /** Routing rules list screen. */
        OpenRouting,
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

    /**
     * The set of tabs currently visible in bottom nav + ViewPager, in display order.
     * Default mode is 4 tabs (no Operator). Operator appears between Routing and
     * Settings when the operator brand is active; when the brand additionally sets
     * Hide-Routing=true, Operator replaces Routing in place (still 4 tabs).
     */
    internal var activeTabs: List<MainTab> = DEFAULT_TABS

    private companion object {
        private val DEFAULT_TABS = listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Settings)
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
    val profilePickerOpened = Channel<Profile>(Channel.CONFLATED)
    /** Profiles-tab manager: drag-reorder committed + update-all tapped. */
    val profileReorderRequests = Channel<List<Profile>>(Channel.UNLIMITED)
    val profileUpdateAllRequests = Channel<Unit>(Channel.UNLIMITED)

    internal val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    internal val clickGuard = ClickGuard<Request>(600L, SystemClock::elapsedRealtime)

    internal var clashRunningState: Boolean = false
    internal var tunnelStartingState: Boolean = false
    /**
     * Breath loop for the power button + concentric rings — slow infinite
     * "ripple outwards" pulse that plays **only** while the tunnel is
     * running. Each layer (button → halo → inner ring → outer ring) shares
     * the same 2s period but starts with an increasing [startDelay], so the
     * inhale visually radiates from the button outward instead of all four
     * layers pulsing in lockstep. Idle and connecting states pin every
     * animated property back to its static value — no motion distraction.
     *
     * Held as separate animators so we can cancel them all in one place.
     */
    internal var powerBreathAnimator: ValueAnimator? = null
    internal var powerHaloBreathAnimator: ValueAnimator? = null
    internal var powerRingInnerBreathAnimator: ValueAnimator? = null
    internal var powerRingOuterBreathAnimator: ValueAnimator? = null
    internal var powerSweepAnimator: ValueAnimator? = null

    /**
     * The three ambient power effects (button breath, halo breath, conic sweep) are driven by a
     * SINGLE animator that ticks the clock; each vsync we derive all three values from elapsed
     * time. This replaced three separate INFINITE ValueAnimators — a method trace showed their
     * per-vsync machinery (Keyframe/clampFraction ×3) dominating the main thread on the connected
     * dashboard. One driver = one machinery pass, and all view writes land in a single frame gate
     * (~30fps) so the redraw fires once, not three staggered times. Visual output is identical:
     * the formulas reproduce each animator's period, interpolator and phase exactly.
     */
    internal var ambientStartNs = 0L
    internal var lastAmbientFrameNs = 0L

    /**
     * Tracks the running flag the breath animators were last spun up for, so
     * repeated [applyPowerVisuals] calls (brand updates, profile refresh,
     * mode toggles) don't restart the breath mid-cycle and visibly blip the
     * rings. Only a real state transition (running ↔ not-running) tears down
     * and rebuilds the animators.
     */
    internal var lastBreathRunning: Boolean? = null
    internal val hostLifecycle = (context.hostActivity() as? LifecycleOwner)?.lifecycle
    internal val powerLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> updateBreathAnimator(clashRunningState, tunnelStartingState)
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> updateBreathAnimator(false, false)
            else -> Unit
        }
    }
    internal var brandHolder: com.github.kr328.clash.design.branding.BrandHolder =
        com.github.kr328.clash.design.branding.BrandHolder.EMPTY
    internal val uiStore = UiStore(context)
    internal val expandedProfileUuids: LinkedHashSet<UUID> = linkedSetOf()
    /** Profiles-tab cards expanded into their proxy groups (independent of the Home set). */
    internal val tabExpandedProfileUuids: LinkedHashSet<UUID> = linkedSetOf()
    internal var currentModeSegment: TunnelState.Mode = TunnelState.Mode.Rule
    private var suppressModeSegment = false
    private var activeProfileForQuickActions: Profile? = null
    internal var activeAnnouncementSupportUrl: String? = null
    private var activeAnnouncementOnOpenUrl: ((String) -> Unit)? = null
    private var activeAnnouncementOnSupport: (() -> Unit)? = null

    internal val profileAdapter = createHomeProfileAdapter()

    /**
     * Profiles-tab manager list: all profiles. Tap = activate (no "Use" button); the chevron
     * expands the card into its proxy groups (rich dashboard); per-profile actions via ⋮ menu.
     */
    internal val tabProfileAdapter = createTabProfileAdapter()
    internal var tabProfilesAll: List<Profile> = emptyList()
    internal val tabItemTouchHelper = createTabItemTouchHelper()

    override val root: View
        get() = binding.root

    fun getExpandedProfileUuids(): Set<UUID> = (expandedProfileUuids + tabExpandedProfileUuids).toSet()

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
            val hasAnnouncement = message.isNotBlank()
            activeAnnouncementSupportUrl = support
            activeAnnouncementOnOpenUrl = onOpenUrl
            activeAnnouncementOnSupport = onSupport

            // Brand name always wins for the header title; announcement no longer
            // doubles into the header summary (the banner below carries it).
            val brandName = brandHolder.manifest.name?.takeIf { it.isNotBlank() }
            binding.mainHeaderTitle.text = brandName ?: context.getString(R.string.launch_name_meta)
            binding.mainHeaderSummary.visibility = View.GONE
            binding.mainHeaderSummary.setOnClickListener(null)
            binding.mainHeaderSummary.isClickable = false

            binding.mainAnnouncementCard.visibility = if (hasAnnouncement) View.VISIBLE else View.GONE
            if (hasAnnouncement) {
                binding.mainAnnouncementPreview.text = message.replace('\n', ' ')

                val hash = announcementHash(message, announcementUrl, support, sourceName)
                val unread = sourceUuid != null && uiStore.announcementReadHashFor(sourceUuid) != hash
                binding.mainAnnouncementNewDot.visibility = if (unread) View.VISIBLE else View.GONE

                binding.mainAnnouncementCard.setOnClickListener {
                    if (sourceUuid != null) {
                        uiStore.setAnnouncementReadHashFor(sourceUuid, hash)
                        binding.mainAnnouncementNewDot.visibility = View.GONE
                    }
                    com.github.kr328.clash.design.dialog.AnnouncementSheet.show(
                        context = context,
                        text = message,
                        url = announcementUrl,
                        supportUrl = support,
                        sourceName = sourceName,
                        onOpenUrl = { target -> onOpenUrl?.invoke(target) },
                        onSupport = onSupport.takeIf { support != null || onSupport != null },
                    )
                }
            } else {
                binding.mainAnnouncementCard.setOnClickListener(null)
                binding.mainAnnouncementNewDot.visibility = View.GONE
            }

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
     * header logo + brand name + tagline + accent override on power button.
     * Re-applies announcement-derived title afterwards so brand wins over
     * the default ClashFest text but announcement-card logic still chooses
     * its own title.
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
            if (profiles.isNotEmpty() && context.isTelevision()) {
                binding.executePendingBindings()
                focusPowerOrbIfIdle()
            }
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
                val activeIds = profiles.filter { it.active }.map { it.uuid }.toSet()
                tabExpandedProfileUuids.retainAll { it in ids && it in activeIds }
                patchDataSet(this::profiles, sortTabProfiles(profiles), id = { it.uuid })
            }
            tabProfileAdapter.setExpandedUuids(tabExpandedProfileUuids.toSet())
            binding.profilesTabList.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
            binding.profilesTabEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            val tabUpdatable = profiles.any { it.imported && it.type != Profile.Type.File }
            binding.profilesTabUpdate.visibility = if (tabUpdatable) View.VISIBLE else View.GONE
        }
    }

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
            tabProfileAdapter.setExpandedUuids(tabExpandedProfileUuids.toSet())
        }
    }

    suspend fun patchGroupSelections(selections: Map<String, String>) {
        withContext(Dispatchers.Main) {
            profileAdapter.setGroupSelections(selections)
        }
    }

    suspend fun patchProxyDetails(details: Map<String, ProxyGroup>) {
        withContext(Dispatchers.Main) {
            profileAdapter.setProxyDetails(details)
            tabProfileAdapter.setProxyDetails(details)
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
            tabProfileAdapter.clearProxyDetails()
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
            if (clickGuard.isOpen(Request.OpenAbout)) return@withContext
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
                this.coreVersion = coreVersion
                runCatching {
                    aboutAppIcon.setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
                }
            }
            val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
            dialog.setContentView(binding.root)
            applyBrandToAbout(binding)

            // Hidden dev brand tester: tap the app icon ×10 (debug builds only).
            val debuggable = (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                var iconTaps = 0
                binding.aboutAppIcon.setOnClickListener {
                    if (++iconTaps >= 10) {
                        iconTaps = 0
                        showBrandDevDialog()
                    }
                }
            }

            binding.aboutGithubIcon.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    runCatching {
                        val url = context.getString(R.string.clashfest_repo_url)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            binding.aboutTelegramIcon.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    runCatching {
                        val url = context.getString(R.string.clashfest_telegram_url)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            val support = uiStore.supportUrl.takeIf { it.isNotBlank() }
            if (support != null) {
                binding.aboutSupportButton.apply {
                    visibility = View.VISIBLE
                    text = context.getString(R.string.about_support)
                    icon = null
                    setOnClickListener {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(support))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }

            if (onCheckUpdates != null) {
                // Status line shows the current/known update state up front (so "About & updates"
                // reflects a cached update without re-checking); the button is the action.
                val statusLine = binding.aboutUpdateStatus
                fun showStatus(text: String?) {
                    statusLine.text = text.orEmpty()
                    statusLine.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                showStatus(initialUpdateStatus)

                binding.aboutCheckUpdatesButton.apply {
                    visibility = View.VISIBLE

                    fun setLoading(loading: Boolean) {
                        isEnabled = !loading
                        alpha = if (loading) 0.65f else 1f
                        text = context.getString(
                            if (loading) R.string.about_checking_updates else R.string.about_check_updates,
                        )
                    }

                    setOnClickListener {
                        if (!isEnabled) return@setOnClickListener
                        showStatus(null)
                        setLoading(true)
                        onCheckUpdates(::setLoading) { text -> showStatus(text) }
                    }
                }
            }

            if (!clickGuard.tryOpen(Request.OpenAbout)) return@withContext
            dialog.setOnDismissListener {
                clickGuard.close(Request.OpenAbout)
                applyPowerVisuals()
            }
            try {
                updateBreathAnimator(false, false)
                dialog.show()
            } catch (error: Throwable) {
                clickGuard.close(Request.OpenAbout)
                applyPowerVisuals()
                throw error
            }
        }
    }

    /** Held so [rebuildMainPagerForTabs] can detach/reattach without piling up. */
    internal var pagerPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    internal var pagerInitialised: Boolean = false

    /**
     * Compute the desired tab set from the active brand and rebuild the pager
     * if it changed. No-op when the layout is the same.
     *
     * The Operator tab is **explicit opt-in** via X-Brand-Show-Operator-Tab —
     * just having a brand name / logo / accent applies the visual brand but
     * does NOT add a tab. Hide-Routing only takes effect when the operator
     * also opted into the tab, otherwise it'd hide Routing without anything
     * replacing it.
     */
    /**
     * Bottom-nav set for the active brand. `X-Brand-Hide-Routing` removes the Routing tab on
     * its own (three tabs); `X-Brand-Show-Operator-Tab` adds Operator, and with both set
     * Operator takes Routing's slot. The original brand commit (3e8fb36a) honoured Hide-Routing
     * alone; the same-day palette refactor accidentally gated it behind the Operator tab.
     */
    internal fun reconcileTabsForBrand() {
        val brand = brandHolder.manifest
        val showTab = brand.showOperatorTab == true
        val hideRouting = brand.hideRouting == true
        val desired = when {
            !brandHolder.isActive -> DEFAULT_TABS
            showTab && hideRouting -> listOf(
                MainTab.Home, MainTab.Profiles, MainTab.Operator, MainTab.Settings,
            )
            showTab -> listOf(
                MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Operator, MainTab.Settings,
            )
            hideRouting -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Settings)
            else -> DEFAULT_TABS
        }.let { if (tvHidesAdvancedTools()) it - MainTab.Routing else it }
        if (desired == activeTabs) return
        // The tab shown now, read against the old set: the same pager position is another tab
        // once the set changes (Settings, third of Home/Profiles/Settings, would become Routing).
        val shown = if (pagerInitialised) currentTab else null
        activeTabs = desired
        if (pagerInitialised) rebuildMainPagerForTabs(shown)
        if (context.isTelevision()) renderTvSettingsEntries()
    }

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

    internal var touchSlopTweakApplied: Boolean = false

    /** TV: the control each tab's content last had, so RIGHT from the nav rail returns to it. */
    internal val tvContentFocus = HashMap<MainTab, java.lang.ref.WeakReference<View>>()

    /** TV: the pending tab switch for the rail item focus rests on (see scheduleRailTabSwitch). */
    internal var tvRailSwitch: Runnable? = null
    /**
     * Toggle the in-header update indicator. MainActivity polls
     * [AppUpdateChecker.isUpdateAvailable] from onResume and from background
     * opportunistic checks, then calls this to reflect the result.
     */
    fun setUpdateBadgeVisible(visible: Boolean) {
        binding.mainHeaderUpdateBadge.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Switch to the Profiles tab (the in-app profile manager) instead of opening a separate screen. */
    fun openProfilesTab() = selectMainTab(MainTab.Profiles)

    /**
     * TV BACK on a tab other than Home goes to Home first (the nav is the only way between tabs
     * there, so BACK leaving the app from Settings was a surprise); BACK on Home exits as before.
     * Returns false when BACK should take its default path. Phones never call this.
     */
    fun handleTvBack(): Boolean {
        if (!context.isTelevision()) return false
        val tab = currentTab ?: return false
        if (tab == MainTab.Home || MainTab.Home !in activeTabs) return false
        selectMainTab(MainTab.Home)
        focusNavItem(MainTab.Home)
        return true
    }

    /** The logical tab currently shown — captured by MainActivity before a soft recreate (D5). */
    val currentTab: MainTab?
        get() = mainTabForPagerPosition(binding.mainPager.currentItem)

    /**
     * Non-animated tab restore after a soft recreate. Reuses the pager-position math of
     * [selectMainTab] but skips the smooth scroll + scroll-to-top behavior — this runs on a
     * freshly inflated design before it is even attached. No-op if [tab] isn't in the current
     * tab set (e.g. the Operator tab vanished with the brand that owned it).
     */
    fun selectTab(tab: MainTab) {
        val logicalIndex = activeTabs.indexOf(tab)
        if (logicalIndex < 0) return
        binding.mainPager.setCurrentItem(logicalIndex + 1, false)
        renderMainTab(tab)
    }

    init {
        binding.self = this
        binding.tunnelStarting = false
        binding.hasProfiles = false
        applyHomeBackgroundStyle()
        if (context.isTelevision()) {
            applyTvNavigationRail()
            // Settle the TV tab set (no Routing without the advanced tools) before the pager is
            // built, so the rail doesn't show Routing for a moment and then drop it.
            reconcileTabsForBrand()
        }
        setupMainPager()

        // Surface the remote-control quick entry on the TV home (deep settings nav with a D-pad
        // is painful); on phones it lives in the "+" sheet instead. Shown in both the empty and
        // active home states so a fresh TV can enable it to receive its first subscription.
        if (context.isTelevision()) {
            binding.mainCompanionRow.visibility = View.VISIBLE
            binding.mainCompanionEmptyButton.visibility = View.VISIBLE
            applyTvHomeLayout()
            applyTvEmptyState()
            applyTvPageFocusMargins()
            applyTvProfilesHeader()
            applyTvSettingsEntries()
        }

        setupProfileLists()

        setupPowerCard()

        binding.tunnelStarting = false
        applyPowerVisuals()

        binding.mainNavHome.setOnClickListener { selectMainTab(MainTab.Home) }
        binding.mainNavProfiles.setOnClickListener { selectMainTab(MainTab.Profiles) }
        binding.mainNavRouting.setOnClickListener { selectMainTab(MainTab.Routing) }
        binding.mainNavOperator.setOnClickListener { selectMainTab(MainTab.Operator) }
        binding.mainNavSettings.setOnClickListener { selectMainTab(MainTab.Settings) }
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
                if (profile.imported && profile.type != Profile.Type.File) {
                    profileForceUpdateRequests.trySend(profile)
                }
            }
        }
        binding.mainActiveProfileSupport.setOnClickListener {
            val target = resolveSupportUrl() ?: return@setOnClickListener
            activeAnnouncementOnOpenUrl?.invoke(target) ?: activeAnnouncementOnSupport?.invoke()
        }
    }

    private fun applyHomeBackgroundStyle() {
        if (context.isTelevision()) {
            // TV: every tab page paints bg_home_canvas inside the centred content column and the
            // root only shows in the side margins; the same (centred) canvas there makes the column
            // edge disappear instead of leaving darker bands.
            binding.root.setBackgroundResource(R.drawable.bg_home_canvas)
            return
        }
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
