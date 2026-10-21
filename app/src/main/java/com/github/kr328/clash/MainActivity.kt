package com.github.kr328.clash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.main.MainBypassPrompter
import com.github.kr328.clash.main.MainConnectionController
import com.github.kr328.clash.main.MainDashboardFetcher
import com.github.kr328.clash.main.MainImportFlows
import com.github.kr328.clash.main.MainProfileActions
import com.github.kr328.clash.main.MainProxyProbe
import com.github.kr328.clash.main.MainSubscriptionMetadata
import com.github.kr328.clash.main.MainTunnelModeController
import com.github.kr328.clash.main.MainUpdateController
import com.github.kr328.clash.main.openExternalUrl
import com.github.kr328.clash.qr.ScanQrCode
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.shouldPollHomeTraffic
import com.github.kr328.clash.util.showProfileQuickEditSheet
import com.github.kr328.clash.util.withProfile
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

class MainActivity : BaseActivity<MainDesign>() {
    private val scanLauncher: ActivityResultLauncher<Unit?> =
        registerForActivityResult(ScanQrCode()) { imports.onScanResult(it) }

    private val tunnel = MainTunnelModeController(this)
    private val probe = MainProxyProbe(this)
    private val dashboard = MainDashboardFetcher(this, tunnel, probe)
    private val subscriptionMetadata = MainSubscriptionMetadata(this)
    private val imports: MainImportFlows = MainImportFlows(this, tunnel, dashboard) { scanLauncher.launch(null) }
    private val bypass = MainBypassPrompter(this)
    private val connection = MainConnectionController(this, dashboard, probe, bypass, imports)
    private val profileActions = MainProfileActions(this, tunnel, dashboard, subscriptionMetadata)
    private val updates = MainUpdateController(this)

    /** How [runDashboard] resolved. Cancellation (activity destroy) exits by exception instead. */
    private enum class DashboardExit { SoftRecreate }

    /**
     * Doorbell: the current design's captured theme no longer matches desired state (brand
     * accent changed, day/night flipped). Conflated — a burst of triggers collapses into one
     * re-inflation, and the accent staleness check is level-based (desired vs applied), so an
     * already-converged run never re-fires.
     */
    private val softRecreateRequests =
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    // The helpers in com.github.kr328.clash.main reach the protected BaseActivity state through these.
    internal val store: UiStore
        get() = uiStore
    internal val currentDesign: MainDesign?
        get() = design
    internal val isClashRunning: Boolean
        get() = clashRunning

    /**
     * Context for dialogs/menus/sheets spawned by MainActivity. The design's themed wrapper —
     * the brand accent lives there, never on the Activity theme (see
     * [themeOverlaysOnActivityTheme]). Falls back to the activity before the first design lands.
     */
    internal val themedContext: Context
        get() = design?.context ?: this

    /**
     * Main inflates its designs from [com.github.kr328.clash.design.branding.BrandThemeApplier
     * .themedContextFor] wrappers so the accent path never has to destroy the Activity
     * (Android 16 ContentCapture SIGABRT) — the Activity theme itself stays a virgin
     * config-driven base theme that overlays are never baked into.
     */
    override val themeOverlaysOnActivityTheme: Boolean = false

    internal fun resolveStatusSnapshot(): StatusClient.StatusSnapshot =
        runCatching { StatusClient(this).statusSnapshot() }
            .getOrDefault(StatusClient.StatusSnapshot(clashRunning, null))

    override suspend fun main() {
        // Design-generation loop (soft recreate, D2): the Activity object survives accent and
        // day/night changes — destroying it probabilistically SIGABRTs the process on Android 16
        // (platform ContentCapture teardown bug). Each iteration derives a fresh themed wrapper
        // from current state, inflates a new MainDesign from it, and runs the dashboard loop
        // until the captured theme goes stale.
        var restoreTab: MainDesign.MainTab? = null
        while (true) {
            val themed = com.github.kr328.clash.design.branding.BrandThemeApplier.themedContextFor(this)
            val design = MainDesign(themed)
            design.applyInitialModeFromPreference(uiStore.tunnelModePreference)
            restoreTab?.let { design.selectTab(it) }
            val previous = this.design
            setContentDesign(design)
            // System bars follow the wrapper theme — the Activity theme is deliberately virgin.
            applyWindowAppearance(themed)
            if (intent?.action == Intents.ACTION_OPEN_NODE_PICKER) {
                openNodePickerFromIntent()
            }
            if (intent?.action == Intents.ACTION_OPEN_PROFILES) {
                openProfilesFromIntent()
            }
            // Mirror onDestroy for the replaced design; its jobs/tickers already died with the
            // previous runDashboard scope.
            previous?.cancel()
            com.github.kr328.clash.common.log.Log.d(
                "soft-recreate: dashboard inflated on live activity@" +
                    Integer.toHexString(System.identityHashCode(this)),
            )

            val exit = runDashboard(design)
            restoreTab = design.currentTab
            if (exit != DashboardExit.SoftRecreate) break
        }
    }

    /**
     * The dashboard event loop — the moved (not rewritten) body of the pre-soft-recreate [main].
     * Wrapped in [supervisorScope] so a failed local action does not stop the request consumer.
     * Resolving cancels every child (tickers, the refresh
     * consumer, proxy-detail jobs) via structured concurrency, with no manual bookkeeping of the
     * old run. Only a [softRecreateRequests] signal resolves it; activity teardown cancels it by
     * exception like before.
     */
    private suspend fun runDashboard(design: MainDesign): DashboardExit = supervisorScope {
        // Handed to the helpers that launch their own children, so those stay inside this run's scope.
        val dashboardScope = this
        // A fresh design starts from its default traffic label; drop the memo so the first
        // fetchTraffic of this run always pushes the real total.
        dashboard.resetForNewRun()
        // These are normally reset by launched children (delayed unlock / finally), which die
        // with this scope on a soft recreate — clear them so a recreate mid-flight can't wedge
        // the power button or the About update check for the rest of the session.
        connection.resetForNewRun()
        updates.resetForNewRun()

        updates.bindAbout(dashboardScope, design)

        // Keep the companion agent alive whenever the app is open if the user enabled it — the
        // foreground service doesn't survive a process restart on its own, so a paired controller
        // could otherwise never reach this device after the app was killed.
        if (com.github.kr328.clash.companion.CompanionStore(this@MainActivity).agentEnabled &&
            !com.github.kr328.clash.companion.agent.CompanionAgent.isReady()
        ) {
            com.github.kr328.clash.companion.agent.CompanionGatewayService.start(this@MainActivity)
        }
        design.onUpdateBadgeTap = { updates.showUpdateAvailableDialog() }
        updates.refreshUpdateBadge(design)

        design.onOpenBrandUrl = { url -> openExternalUrl(url) }

        val tickerInteractive = ticker(TimeUnit.SECONDS.toMillis(2))
        // Bumped from 8s -> 30s. While the screen is off (or activity is in background),
        // the dashboard is invisible; we only need infrequent updates so totals stay
        // roughly accurate when the user comes back.
        val tickerIdle = ticker(TimeUnit.SECONDS.toMillis(30))
        val profileTicker = ticker(TimeUnit.MINUTES.toMillis(2))
        val refreshRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val proxyDetailRequests =
            kotlinx.coroutines.channels.Channel<Pair<Profile, String>>(kotlinx.coroutines.channels.Channel.CONFLATED)
        var announcementRefreshPending = false
        var proxyDetailJob: Job? = null
        var homeProxyPatchJob: Job? = null
        var trafficRefreshJob: Job? = null
        var lastDashboardRefreshRequestAt = 0L

        fun scheduleDashboardRefresh(includeAnnouncement: Boolean = false, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && !includeAnnouncement && now - lastDashboardRefreshRequestAt < 1200L) {
                return
            }
            lastDashboardRefreshRequestAt = now
            if (includeAnnouncement) {
                announcementRefreshPending = true
            }
            refreshRequests.trySend(Unit)
        }

        fun scheduleProxyDetailsRefresh(profile: Profile, group: String) {
            if (group.isNotBlank()) {
                proxyDetailRequests.trySend(profile to group)
            }
        }

        fun scheduleTrafficRefresh() {
            if (!shouldPollHomeTraffic(clashRunning, activityStarted, design.currentTab == MainDesign.MainTab.Home) ||
                !Remote.service.remote.isReady || trafficRefreshJob?.isActive == true
            ) return
            trafficRefreshJob = launch {
                try {
                    dashboard.fetchTraffic(design)
                } catch (_: com.github.kr328.clash.common.util.RemoteServiceUnavailableException) {
                    Log.d("Traffic refresh skipped: remote service unavailable")
                }
            }
        }

        launch {
            while (isActive) {
                refreshRequests.receive()

                do {
                    val refreshAnnouncementNow = announcementRefreshPending
                    announcementRefreshPending = false

                    try {
                        val runtime = dashboard.fetch(design)
                        if (!runtime.running ||
                            runtime.activeProfileUuid == null ||
                            runtime.proxyNames.isEmpty()
                        ) {
                            proxyDetailJob?.cancel()
                            proxyDetailJob = null
                        }
                        if (refreshAnnouncementNow) {
                            subscriptionMetadata.refreshAnnouncement(design)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                    }
                } while (refreshRequests.tryReceive().isSuccess)
            }
        }

        // Service startup must not gate local actions such as opening the import sheet.
        scheduleDashboardRefresh(includeAnnouncement = true, force = true)

        var exit: DashboardExit? = null
        while (exit == null) {
            select<Unit> {
                softRecreateRequests.onReceive {
                    exit = DashboardExit.SoftRecreate
                }

                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop,
                        Event.ClashStart,
                        Event.ProfileLoaded,
                        Event.ProfileChanged,
                        // Sub-bug #8: a subscription update can change or clear the brand accent
                        // with no profile switch involved — the refresh below runs fetch(), whose
                        // accent staleness check then converges the theme on this event.
                        Event.ProfileUpdateCompleted -> {
                            // Coalesce expensive dashboard refreshes so a burst of
                            // service/profile broadcasts does not pile up parallel
                            // proxy-group queries and stall follow-up taps.
                            val serviceStateEvent = it == Event.ClashStop ||
                                it == Event.ClashStart ||
                                it == Event.ServiceRecreated
                            if (it == Event.ProfileLoaded) {
                                probe.clearWarmedGroups()
                                design.clearProxyDetails()
                            }
                            scheduleDashboardRefresh(
                                includeAnnouncement = it == Event.ActivityStart ||
                                    it == Event.ProfileChanged ||
                                    // Updates rewrite fetch-headers.json (announce / support /
                                    // brand headers) — re-render the announcement card too.
                                    it == Event.ProfileUpdateCompleted,
                                // ProfileLoaded is the authoritative "engine finished loading the new
                                // profile's config" signal — the UI MUST re-fetch to re-prime the Home
                                // node against the new groups. It must bypass the refresh throttle: on
                                // rapid profile switches ProfileLoaded lands within the throttle window
                                // of the preceding ProfileChanged refresh and was silently dropped, so
                                // the post-load fetch never ran and the Node row stuck on "Choose node".
                                force = serviceStateEvent || it == Event.ProfileLoaded,
                            )
                            if (it == Event.ProfileLoaded || it == Event.ProfileChanged) {
                                launch {
                                    delay(260L)
                                    tunnel.ensureGlobalSelectionSafeWithRetry()
                                }
                            }
                            if (it == Event.ActivityStart) {
                                updates.refreshUpdateBadge(design)
                            }
                            if (it == Event.ProfileUpdateCompleted) {
                                launch { subscriptionMetadata.hintTunStackChange(design) }
                            }
                        }

                        // Sent by NetworkObserveModule after a default-network switch: stale
                        // connections were closed and group health checks forced — re-fetch so
                        // the Home node row shows where the auto groups converged, without
                        // waiting for the next ticker.
                        Event.ConnectionsChanged ->
                            scheduleDashboardRefresh(force = true)

                        Event.ActivityStop -> trafficRefreshJob?.cancel()
                        else -> Unit
                    }
                }

                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus ->
                            connection.toggleStatus(dashboardScope, design) { force ->
                                scheduleDashboardRefresh(force = force)
                            }

                        MainDesign.Request.OpenNewProfile ->
                            imports.showHomeImportSheet(design)

                        MainDesign.Request.OpenConnections ->
                            startActivity(ConnectionsActivity::class.intent)

                        MainDesign.Request.OpenLogs ->
                            startActivity(LogsActivity::class.intent)

                        MainDesign.Request.OpenRouting -> launch {
                            openRulesHub()
                        }

                        MainDesign.Request.RefreshRouting -> launch { refreshRoutingSummary(design) }
                        MainDesign.Request.OpenNewRule -> launch { openRulesHub(addRule = true) }
                        MainDesign.Request.OpenEffectiveRules -> launch { openRulesHub(expandRules = true) }

                        MainDesign.Request.OpenRules -> launch {
                            openRulesHub(expandProviders = true)
                        }

                        MainDesign.Request.OpenProxyChain ->
                            startActivity(ProxyChainActivity::class.intent)

                        MainDesign.Request.OpenPerAppRouting ->
                            startActivity(AccessControlActivity::class.intent)

                        MainDesign.Request.OpenProfiles ->
                            design.openProfilesTab()

                        MainDesign.Request.OpenCompanion ->
                            startActivity(CompanionActivity::class.intent)

                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)

                        MainDesign.Request.OpenThemeSettings ->
                            startActivity(ThemeSettingsActivity::class.intent)

                        MainDesign.Request.OpenLanguageSettings ->
                            startActivity(LanguageSettingsActivity::class.intent)

                        MainDesign.Request.OpenAppSettings ->
                            startActivity(SubscriptionIdentityActivity::class.intent)

                        MainDesign.Request.OpenAbout -> design.onOpenAbout?.invoke()

                        MainDesign.Request.OpenImportClipboard ->
                            launch { imports.importFromClipboard(design) }

                        MainDesign.Request.MainTabChanged -> {
                            if (design.currentTab == MainDesign.MainTab.Home) scheduleTrafficRefresh()
                            else trafficRefreshJob?.cancel()
                        }

                        MainDesign.Request.OpenImportQr ->
                            scanLauncher.launch(null)

                        MainDesign.Request.PatchModeDirect ->
                            launch { tunnel.patchTunnelMode(design, TunnelState.Mode.Rule) { scheduleDashboardRefresh() } }

                        MainDesign.Request.PatchModeGlobal ->
                            launch { tunnel.patchTunnelMode(design, TunnelState.Mode.Global) { scheduleDashboardRefresh() } }

                        MainDesign.Request.PatchModeRule ->
                            launch { tunnel.patchTunnelMode(design, TunnelState.Mode.Rule) { scheduleDashboardRefresh() } }

                        MainDesign.Request.CycleTheme -> {
                            uiStore.darkMode = when (uiStore.darkMode) {
                                DarkMode.ForceLight -> DarkMode.ForceDark
                                else -> DarkMode.ForceLight
                            }
                            // Config-driven day/night: sync AppCompat night mode; because Main
                            // declares configChanges="uiMode", AppCompat delivers the flip as
                            // onConfigurationChanged -> onDayNightChanged -> soft recreate (no
                            // Activity destroy). The old raw recreate() here was both crash-prone
                            // (ContentCapture SIGABRT) and skipped the night-mode sync entirely.
                            syncNightModeFromUiStore()
                            // No-op flip (e.g. ForceLight while already light): still refresh so
                            // the theme-toggle icon reflects the stored preference.
                            scheduleDashboardRefresh()
                        }
                    }
                }

                design.patchHomeProxyRequests.onReceive { (profile, group, name) ->
                    homeProxyPatchJob?.cancel()
                    homeProxyPatchJob = profileActions.patchHomeProxy(
                        dashboardScope, design, profile, group, name,
                        { scheduleDashboardRefresh() },
                        { p, g -> scheduleProxyDetailsRefresh(p, g) },
                    )
                }

                design.profilePingAllRequests.onReceive { (profile, group, nodeNames, testUrl) ->
                    probe.pingProfileNodes(dashboardScope, design, profile, group, nodeNames, testUrl) {
                        scheduleDashboardRefresh()
                    }
                }

                design.proxyPingNodeRequests.onReceive { (profile, group, proxy) ->
                    probe.pingNode(dashboardScope, design, profile, group, proxy)
                }

                design.profileForceUpdateRequests.onReceive { profile ->
                    profileActions.forceUpdate(dashboardScope, design, profile) { scheduleDashboardRefresh() }
                }

                design.profileProxyYamlRequests.onReceive { (profile, _, proxyName) ->
                    launch {
                        val yaml = withProfile { readProxyEntryYaml(profile.uuid, proxyName) }
                        profileActions.showProxyYamlDialog(proxyName, yaml ?: getString(R.string.proxy_yaml_missing))
                    }
                }

                design.profileExpandChanged.onReceive {
                    scheduleDashboardRefresh()
                }

                design.profileVisibleGroupChanged.onReceive { (profile, group) ->
                    uiStore.proxyLastGroup = group
                    scheduleProxyDetailsRefresh(profile, group)
                    // Lazy warmup (O-07): url-test this group's subtree once, only when the user
                    // actually opens it — instead of every auto group up front on connect.
                    probe.warmVisibleGroup(dashboardScope, design, group)
                }

                proxyDetailRequests.onReceive { (profile, group) ->
                    proxyDetailJob?.cancel()
                    proxyDetailJob = launch {
                        try {
                            dashboard.fetchVisibleProxyGroup(design, profile, group)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Runtime detail queries can race profile reload/startup; the next UI event retries.
                        }
                    }
                }

                design.profileActivateRequests.onReceive { profile ->
                    profileActions.activate(dashboardScope, design, profile) { scheduleDashboardRefresh() }
                }

                design.profileMenuRequests.onReceive { (profile, anchor) ->
                    launch(Dispatchers.Main) {
                        profileActions.showProfileOverflowMenu(design, profile, anchor)
                    }
                }

                design.profileEditRequests.onReceive { profile ->
                    showProfileQuickEditSheet(design, profile) { dashboard.fetch(design) }
                }

                design.profileUpdateAllRequests.onReceive {
                    profileActions.updateAll(dashboardScope, design)
                }

                design.profileReorderRequests.onReceive { ordered ->
                    launch { withProfile { reorder(ordered.map { it.uuid.toString() }) } }
                }

                if (shouldPollHomeTraffic(clashRunning, activityStarted, design.currentTab == MainDesign.MainTab.Home)) {
                    val interactive = getSystemService<PowerManager>()?.isInteractive ?: true
                    // Pick the ticker BEFORE registering the select branch so that the idle
                    // ticker (and only it) wakes us when the screen is off, instead of both
                    // tickers competing.
                    val trafficTicker = if (interactive) tickerInteractive else tickerIdle
                    trafficTicker.onReceive {
                        scheduleTrafficRefresh()
                    }
                }

                if (activityStarted) {
                    profileTicker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }

        // Structured teardown of this run: tickers, the refresh consumer and any in-flight
        // launches die here; the design-generation loop in main() takes over.
        coroutineContext.cancelChildren()
        checkNotNull(exit)
    }

    private fun openProfilesFromIntent() {
        design?.let {
            it.openProfilesTab()
            intent.action = null
        }
    }

    /**
     * Notification "Change node" action. On a warm activity the picker opens at once; on a cold
     * start the active profile is not rendered yet, so retry briefly until the design knows it.
     */
    private fun openNodePickerFromIntent() {
        intent?.action = null
        launch {
            repeat(12) {
                if (design?.openNodePickerForActiveProfile() == true) return@launch
                delay(250)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UpdateDownloadActivity.ACTION_SYNC_PENDING_DOWNLOAD) {
            updates.syncPendingApkDownload()
        }
        if (intent.action == Intents.ACTION_OPEN_NODE_PICKER) {
            openNodePickerFromIntent()
        }
        if (intent.action == Intents.ACTION_OPEN_PROFILES) {
            openProfilesFromIntent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()) { _: Boolean -> }

            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        updates.onCreate()

    }

    override fun onResume() {
        super.onResume()
        events.trySend(Event.ActivityStart)
        updates.syncPendingApkDownload()
    }

    override fun onDestroy() {
        updates.onDestroy()
        super.onDestroy()
    }

    override fun onProfileLoaded() {
        connection.onProfileLoaded()
        super.onProfileLoaded()
    }

    /**
     * Day/night flips ride the same soft path as accent changes (4.2): the wrapper for the next
     * design is derived under the new Configuration, and the virgin config-driven Activity theme
     * rebases by itself — no recreate, no ContentCapture teardown.
     */
    override fun onDayNightChanged() {
        softRecreateRequests.trySend(Unit)
    }

    /**
     * External nudge from other screens that used to `recreate()` Main (ThemeSettings palette /
     * accent / true-black changes): re-derive the wrapper from current uiStore state and
     * re-inflate, without destroying the Activity. NOT sufficient for font-scale or language
     * changes — those are baked in attachBaseContext and still need a real recreate.
     */
    fun requestSoftRecreate() {
        softRecreateRequests.trySend(Unit)
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        super.onProfileUpdateCompleted(uuid)
        profileActions.onProfileUpdateCompleted(uuid)
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        super.onProfileUpdateFailed(uuid, reason)
        profileActions.onProfileUpdateFailed(uuid, reason)
    }

    private suspend fun openRulesHub(addRule: Boolean = false, expandProviders: Boolean = false, expandRules: Boolean = false) {
        val uuid = withProfile { queryActive()?.takeIf { it.imported }?.uuid }
        val hubIntent = uuid?.let { RulesHubActivity::class.intent.setUUID(it) }
            ?: RulesHubActivity::class.intent
        startActivity(hubIntent
            .putExtra(RulesHubActivity.EXTRA_ADD_RULE, addRule)
            .putExtra(RulesHubActivity.EXTRA_EXPAND_PROVIDERS, expandProviders)
            .putExtra(RulesHubActivity.EXTRA_EXPAND_RULES, expandRules))
    }

    private var routingSummaryGeneration = 0
    private suspend fun refreshRoutingSummary(design: MainDesign) {
        val generation = ++routingSummaryGeneration
        val profile = withProfile { queryActive()?.takeIf { it.imported } }
        if (generation != routingSummaryGeneration) return
        design.patchRoutingSummary(profile?.name, null)
        if (profile == null) return
        val result = runCatching {
            val text = withProfile { readRuleEditorBundle(profile.uuid) } ?: error("Missing rule state")
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
                com.github.kr328.clash.service.model.RuleEditorBundle.serializer(), text).state
        }
        if (generation == routingSummaryGeneration) design.patchRoutingSummary(profile.name, result.getOrNull(), result.isFailure)
    }
}
