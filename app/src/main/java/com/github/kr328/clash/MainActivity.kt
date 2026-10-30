package com.github.kr328.clash

import android.content.Intent
import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.PowerManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.util.updateProfileWithProgress
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.AppUpdateChecker
import com.github.kr328.clash.util.showProfileQuickEditSheet
import com.github.kr328.clash.util.ChangeBus
import com.github.kr328.clash.util.applyUserProxySelection
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.qr.QrScanResult
import com.github.kr328.clash.qr.ScanQrCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity<MainDesign>() {
    internal val proxyRuntime = MainProxyRuntime(this)
    internal val dashboard = MainDashboard(this)
    internal val profileActions = MainProfileActions(this)
    internal val updates = MainAppUpdates(this)
    internal val subscriptionMeta = MainSubscriptionMeta(this)
    private val bypassPrompt = MainBypassPrompt(this)

    internal val mainDesign: MainDesign?
        get() = design

    internal val mainUiStore
        get() = uiStore

    internal val isClashRunning: Boolean
        get() = clashRunning

    internal val scanLauncher = registerForActivityResult(ScanQrCode(), ::onScanResult)
    private var isToggleStatusInFlight: Boolean = false

    /** How [runDashboard] resolved. Cancellation (activity destroy) exits by exception instead. */
    private enum class DashboardExit { SoftRecreate }

    /**
     * Doorbell: the current design's captured theme no longer matches desired state (brand
     * accent changed, day/night flipped). Conflated — a burst of triggers collapses into one
     * re-inflation, and the accent staleness check is level-based (desired vs applied), so an
     * already-converged run never re-fires.
     */
    internal val softRecreateRequests =
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

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

    private fun onScanResult(result: QrScanResult) {
        launch {
            val d = design ?: return@launch
            when (result) {
                is QrScanResult.Success -> {
                    val url = result.content
                    if (url.isNotBlank()) {
                        profileActions.importSubscriptionFromUrl(d, url)
                    }
                }

                QrScanResult.Canceled -> Unit
                QrScanResult.MissingPermission ->
                    d.showExceptionToast(getString(R.string.import_from_qr_no_permission))

                is QrScanResult.Error ->
                    d.showExceptionToast(getString(R.string.import_from_qr_exception))
            }
        }
    }

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
     * Wrapped in [coroutineScope] so resolving cancels every child (tickers, the refresh
     * consumer, proxy-detail jobs) via structured concurrency, with no manual bookkeeping of the
     * old run. Only a [softRecreateRequests] signal resolves it; activity teardown cancels it by
     * exception like before.
     */
    private suspend fun runDashboard(design: MainDesign): DashboardExit = coroutineScope {
        // A fresh design starts from its default traffic label; drop the memo so the first
        // fetchTraffic of this run always pushes the real total.
        dashboard.lastForwardedTrafficTotal = Long.MIN_VALUE
        // These are normally reset by launched children (delayed unlock / finally), which die
        // with this scope on a soft recreate — clear them so a recreate mid-flight can't wedge
        // the power button or the About update check for the rest of the session.
        isToggleStatusInFlight = false
        updates.isCheckingUpdates = false

        with(updates) { this@coroutineScope.bindOpenAbout(design) }

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

        design.onOpenBrandUrl = { url -> subscriptionMeta.openExternalUrl(url) }
        design.onOpenDesyncSettings = { profile ->
            startActivity(DesyncSettingsActivity.settingsIntent(profile.uuid))
        }

        val tickerInteractive = ticker(TimeUnit.SECONDS.toMillis(2))
        // Bumped from 8s -> 30s. While the screen is off (or activity is in background),
        // the dashboard is invisible; we only need infrequent updates so totals stay
        // roughly accurate when the user comes back.
        val tickerIdle = ticker(TimeUnit.SECONDS.toMillis(30))
        val profileTicker = ticker(TimeUnit.MINUTES.toMillis(2))
        // Desync-only status on the Home card: polled only while Home is on screen (below).
        val desyncTicker = ticker(TimeUnit.SECONDS.toMillis(5))
        val refreshRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val proxyDetailRequests =
            kotlinx.coroutines.channels.Channel<Pair<Profile, String>>(kotlinx.coroutines.channels.Channel.CONFLATED)
        var announcementRefreshPending = false
        var proxyDetailJob: Job? = null
        var homeProxyPatchJob: Job? = null
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

        launch {
            while (isActive) {
                refreshRequests.receive()

                do {
                    val refreshAnnouncementNow = announcementRefreshPending
                    announcementRefreshPending = false

                    try {
                        val runtime = with(dashboard) { design.fetch() }
                        if (!runtime.running ||
                            runtime.activeProfileUuid == null ||
                            runtime.proxyNames.isEmpty()
                        ) {
                            proxyDetailJob?.cancel()
                            proxyDetailJob = null
                        }
                        if (refreshAnnouncementNow) {
                            subscriptionMeta.refreshAnnouncement(design)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                    }
                } while (refreshRequests.tryReceive().isSuccess)
            }
        }

        // A paired controller (companion remote) changed the mode or the node through the same
        // shared paths the Home controls use. Adopt it: drop our own converging pick and re-read
        // the persisted state, so the dashboard follows the remote instead of reverting it.
        // Profile switches and VPN on/off already arrive through the service broadcasts above.
        launch {
            val remoteChanges = kotlinx.coroutines.channels.Channel<ChangeBus.Change>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val listener = ChangeBus.Listener { change, remote ->
                if (remote) remoteChanges.trySend(change)
            }
            ChangeBus.addListener(listener)
            try {
                for (change in remoteChanges) {
                    when (change) {
                        ChangeBus.Change.Mode -> {
                            dashboard.adoptExternalTunnelMode()
                            scheduleDashboardRefresh(force = true)
                        }
                        ChangeBus.Change.Proxies -> {
                            val active = runCatching { withProfile { queryActive() } }.getOrNull()
                            if (active != null) scheduleProxyDetailsRefresh(active, uiStore.proxyLastGroup)
                            scheduleDashboardRefresh(force = true)
                        }
                        else -> Unit
                    }
                }
            } finally {
                ChangeBus.removeListener(listener)
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
                                // The engine (re)loaded a config: a subscription update while
                                // connected can change group membership. Live group details are
                                // merged, never replaced, and a cached group is not re-queried, so
                                // the picker kept the old members until a reconnect. Drop them and
                                // let the refresh below re-prime from the new engine state.
                                proxyRuntime.warmedGroups.clear()
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
                                    proxyRuntime.ensureGlobalSelectionSafeWithRetry()
                                }
                            }
                            if (it == Event.ActivityStart) {
                                updates.refreshUpdateBadge(design)
                            }
                            if (it == Event.ProfileUpdateCompleted) {
                                launch { subscriptionMeta.hintTunStackChange(design) }
                            }
                        }

                        // Sent by NetworkObserveModule after a default-network switch: stale
                        // connections were closed and group health checks forced — re-fetch so
                        // the Home node row shows where the auto groups converged, without
                        // waiting for the next ticker.
                        Event.ConnectionsChanged ->
                            scheduleDashboardRefresh(force = true)

                        else -> Unit
                    }
                }

                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            launch {
                                if (isToggleStatusInFlight) return@launch
                                isToggleStatusInFlight = true
                                try {
                                    // Optimistic visual: flip the power button OFF right now from the
                                    // locally-known state, before the status IPC + service call. On the
                                    // gVisor stack the service process is CPU-saturated during teardown,
                                    // so the snapshot IPC can lag a few hundred ms — that lag was blocking
                                    // the button repaint and making disconnect "feel" slow even though the
                                    // engine tears down fast. The real state reconciles via the ClashStop
                                    // broadcast (-> fetch -> setClashRunning) right after.
                                    val optimisticStop = clashRunning
                                    if (optimisticStop) {
                                        // Latch BEFORE the flip so any refresh racing us can't revive ON.
                                        dashboard.suppressRunningReviveUntil = SystemClock.uptimeMillis() + 8000L
                                        design.setClashRunning(false)
                                        design.setTunnelStarting(false)
                                    }
                                    val runningNow = withContext(Dispatchers.IO) {
                                        proxyRuntime.resolveStatusSnapshot().serviceRunning
                                    }
                                    Remote.broadcasts.clashRunning = runningNow
                                    if (runningNow) {
                                        dashboard.suppressRunningReviveUntil = SystemClock.uptimeMillis() + 8000L
                                        stopClashService()
                                        design.setClashRunning(false)
                                        design.setTunnelStarting(false)
                                    } else {
                                        // User is (re)starting — drop any stale stop latch.
                                        dashboard.suppressRunningReviveUntil = 0L
                                        if (!bypassPrompt.maybePromptRuBypass()) {
                                            return@launch
                                        }
                                        design.setTunnelStarting(true)
                                        try {
                                            with(dashboard) { design.startClash() }
                                        } finally {
                                            scheduleDashboardRefresh()
                                        }
                                    }
                                } finally {
                                    // Keep lock short so rapid taps don't enqueue duplicate start/stop sequences.
                                    launch {
                                        delay(350L)
                                        isToggleStatusInFlight = false
                                    }
                                    scheduleDashboardRefresh(force = true)
                                }
                            }
                        }

                        MainDesign.Request.OpenNewProfile ->
                            profileActions.showHomeImportSheet(design)

                        MainDesign.Request.OpenConnections ->
                            startActivity(ConnectionsActivity::class.intent)

                        MainDesign.Request.OpenLogs ->
                            startActivity(LogsActivity::class.intent)

                        MainDesign.Request.OpenRouting -> launch {
                            profileActions.openRulesHub()
                        }

                        MainDesign.Request.OpenRules -> launch {
                            profileActions.openRulesHub()
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

                        MainDesign.Request.OpenAppSettings ->
                            startActivity(SubscriptionIdentityActivity::class.intent)

                        MainDesign.Request.OpenAbout -> design.onOpenAbout?.invoke()

                        MainDesign.Request.OpenImportClipboard ->
                            profileActions.importFromClipboard(design)

                        MainDesign.Request.OpenImportQr ->
                            scanLauncher.launch(null)

                        MainDesign.Request.PatchModeDirect ->
                            launch { with(dashboard) { design.patchTunnelMode(TunnelState.Mode.Rule) { scheduleDashboardRefresh() } } }

                        MainDesign.Request.PatchModeGlobal ->
                            launch { with(dashboard) { design.patchTunnelMode(TunnelState.Mode.Global) { scheduleDashboardRefresh() } } }

                        MainDesign.Request.PatchModeRule ->
                            launch { with(dashboard) { design.patchTunnelMode(TunnelState.Mode.Rule) { scheduleDashboardRefresh() } } }

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
                    homeProxyPatchJob = launch {
                        try {
                            val runningNow = withContext(Dispatchers.IO) {
                                proxyRuntime.resolveStatusSnapshot().serviceRunning
                            }
                            Remote.broadcasts.clashRunning = runningNow
                            if (!runningNow) {
                                if (proxyRuntime.isProxyProviderKeyName(name)) {
                                    scheduleDashboardRefresh()
                                    return@launch
                                }
                                design.markProxySelectionPending(profile, group, name)
                                withProfile {
                                    rememberProxySelection(profile.uuid, group, name)
                                }
                                uiStore.proxyLastGroup = group
                                scheduleDashboardRefresh()
                                return@launch
                            }
                            design.markProxySelectionPending(profile, group, name)
                            val patched = applyUserProxySelection(profile.uuid, group, name, remote = false) { message, duration ->
                                design.showToast(message, duration)
                            }
                            if (!patched) {
                                design.showToast(R.string.proxy_switch_selector_failed, ToastDuration.Long)
                            }
                            scheduleProxyDetailsRefresh(profile, group)
                        } catch (e: CancellationException) {
                            throw e
                        }
                    }
                }

                design.profilePingAllRequests.onReceive { (profile, group, nodeNames, testUrl) ->
                    launch {
                        with(proxyRuntime) {
                            this@launch.pingProfileNodes(design, profile, group, nodeNames, testUrl) { scheduleDashboardRefresh() }
                        }
                    }
                }

                design.proxyPingNodeRequests.onReceive { (profile, group, proxy) ->
                    launch {
                        proxyRuntime.pingSingleNode(design, profile, group, proxy)
                    }
                }

                design.profileForceUpdateRequests.onReceive { profile ->
                    launch {
                        // Success / failure toast comes from the broadcast
                        // observer (onProfileUpdateCompleted / Failed). Don't
                        // toast "started" here — the user sees that *after*
                        // the modal closes, by which point the work is done.
                        runCatching { themedContext.updateProfileWithProgress(profile.uuid) }
                        // Run metadata sync in the background so the UI tap returns
                        // immediately. The earlier race (announcement-card hiding the
                        // active-card support button for a tick) was structural — we
                        // removed that coverage logic, so there's nothing to race now
                        // and we don't need to block the user on a slow HTTP roundtrip.
                        launch(Dispatchers.IO) {
                            uiStore.subscriptionMetadataLastFetch = 0L
                            runCatching { subscriptionMeta.syncSubscriptionMetadata() }
                            withContext(Dispatchers.Main) { subscriptionMeta.refreshAnnouncement(design) }
                        }
                        scheduleDashboardRefresh()
                    }
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

                design.profilePickerOpened.onReceive { profile ->
                    // One engine pass for every group's current choice, so collapsed headers in the
                    // picker show what is selected without loading each group's member list.
                    if (profile.active) {
                        launch {
                            val selections = runCatching {
                                val json = org.json.JSONObject(withClash { queryProxyGroupSelections() })
                                json.keys().asSequence().associateWith { json.optString(it) }
                            }.getOrNull()
                            if (selections != null) design?.patchGroupSelections(selections)
                        }
                    }
                }

                design.profileVisibleGroupChanged.onReceive { (profile, group) ->
                    uiStore.proxyLastGroup = group
                    scheduleProxyDetailsRefresh(profile, group)
                    // Lazy warmup (O-07): url-test this group's subtree once, only when the user
                    // actually opens it — instead of every auto group up front on connect.
                    launch {
                        try {
                            val warmed = proxyRuntime.warmUpGroupSubtree(group)
                            if (warmed.isNotEmpty()) {
                                val patches = proxyRuntime.refreshRuntimeGroupDetails(warmed)
                                if (patches.isNotEmpty()) design?.patchProxyDetails(patches)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }

                proxyDetailRequests.onReceive { (profile, group) ->
                    proxyDetailJob?.cancel()
                    proxyDetailJob = launch {
                        try {
                            with(dashboard) { design.fetchVisibleProxyGroup(profile, group) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Runtime detail queries can race profile reload/startup; the next UI event retries.
                        }
                    }
                }

                design.profileActivateRequests.onReceive { profile ->
                    launch {
                        withProfile {
                            if (profile.imported) {
                                setActive(profile)
                                proxyRuntime.ensureGlobalSelectionSafeWithRetry()
                            } else {
                                design.requestSave(profile)
                            }
                        }
                        scheduleDashboardRefresh()
                    }
                }

                design.profileMenuRequests.onReceive { (profile, anchor) ->
                    launch(Dispatchers.Main) {
                        profileActions.showProfileOverflowMenu(design, profile, anchor)
                    }
                }

                design.profileEditRequests.onReceive { profile ->
                    showProfileQuickEditSheet(design, profile) { with(dashboard) { design.fetch() } }
                }

                design.profileUpdateAllRequests.onReceive {
                    launch {
                        profileActions.updateAllProfiles(design)
                    }
                }

                design.profileReorderRequests.onReceive { ordered ->
                    launch { withProfile { reorder(ordered.map { it.uuid.toString() }) } }
                }

                if (clashRunning && activityStarted) {
                    val interactive = getSystemService<PowerManager>()?.isInteractive ?: true
                    // Pick the ticker BEFORE registering the select branch so that the idle
                    // ticker (and only it) wakes us when the screen is off, instead of both
                    // tickers competing.
                    val trafficTicker = if (interactive) tickerInteractive else tickerIdle
                    trafficTicker.onReceive {
                        launch { with(dashboard) { design.fetchTraffic() } }
                    }
                }

                if (clashRunning && activityStarted && design.showsDesyncOnlyProfile &&
                    design.currentTab == MainDesign.MainTab.Home &&
                    getSystemService<PowerManager>()?.isInteractive != false
                ) {
                    desyncTicker.onReceive {
                        launch { with(dashboard) { design.fetchDesyncStatus() } }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TV: BACK on a non-Home tab returns to Home first; on Home it exits as usual.
        if (isTelevision()) {
            onBackPressedDispatcher.addCallback(this) {
                if (mainDesign?.handleTvBack() == true) return@addCallback
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

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

        if (!updates.downloadReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    updates.apkDownloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(updates.apkDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            updates.downloadReceiverRegistered = true
        }

        // If the installed version is already at/ahead of whatever we last notified about,
        // clear the stale "update available" banner so users don't see it after upgrading.
        runCatching {
            val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
            AppUpdateChecker.resetIfUpdated(this, current)
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

    override fun onResume() {
        super.onResume()
        events.trySend(Event.ActivityStart)
        updates.syncPendingApkDownload()
    }

    override fun onDestroy() {
        if (updates.downloadReceiverRegistered) {
            runCatching { unregisterReceiver(updates.apkDownloadReceiver) }
            updates.downloadReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onProfileLoaded() {
        proxyRuntime.profileLoadEpoch.incrementAndGet()
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
        if (uuid == null) return

        launch {
            val name = withProfile { queryByUUID(uuid)?.name } ?: return@launch
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
            // Surface the one-shot "our merge broke an otherwise-valid config"
            // marker set by ProfileProcessor (the update still committed).
            if (ServiceStore(this@MainActivity).consumeUpdateEngineWarning(uuid)) {
                design?.showToast(
                    getString(R.string.profiles_update_engine_warning),
                    ToastDuration.Long,
                )
            }
            // Rules dropped because their proxy/group vanished from the new
            // subscription (the update still succeeded; the rules were dead).
            val orphaned = ServiceStore(this@MainActivity).consumeOrphanedRulesDropped(uuid)
            if (orphaned.isNotEmpty()) {
                design?.showToast(
                    getString(R.string.profiles_orphaned_rules_dropped, orphaned.size),
                    ToastDuration.Long,
                )
            }
            // Proxy-group members that vanished from the new subscription were pruned so the
            // profile could load (emptied groups were blocked, not sent DIRECT). Tell the user.
            val repairedGroups = ServiceStore(this@MainActivity).consumeProxyGroupsRepaired(uuid)
            if (repairedGroups > 0) {
                design?.showToast(
                    getString(R.string.profiles_proxy_groups_repaired, repairedGroups),
                    ToastDuration.Long,
                )
            }
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        super.onProfileUpdateFailed(uuid, reason)
        if (uuid == null) return

        launch {
            val name = withProfile { queryByUUID(uuid)?.name } ?: return@launch
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason ?: "Unknown"),
                ToastDuration.Long
            ) {
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }

}
