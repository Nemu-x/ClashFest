package com.github.kr328.clash.main

import android.app.Activity
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.LogsActivity
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The power button: connect / disconnect, the pre-connect checks (active profile present and
 * imported, optional refresh, VPN permission) and the post-connect warm-up. Owns the in-flight
 * toggle lock and the profile-load epoch that the warm-up waits on.
 */
internal class MainConnectionController(
    private val activity: MainActivity,
    private val dashboard: MainDashboardFetcher,
    private val probe: MainProxyProbe,
    private val bypass: MainBypassPrompter,
    private val imports: MainImportFlows,
) {
    private val uiStore get() = activity.store
    private val design get() = activity.currentDesign
    private val clashRunning get() = activity.isClashRunning

    private var isToggleStatusInFlight: Boolean = false

    /** Incremented on each [onProfileLoaded] — used to await post-load selector patches. */
    private val profileLoadEpoch = AtomicInteger(0)

    /** Cleared with the run's scope: its launched children (and their `finally`) die on a soft recreate. */
    fun resetForNewRun() {
        isToggleStatusInFlight = false
    }

    fun onProfileLoaded() {
        profileLoadEpoch.incrementAndGet()
    }

    fun toggleStatus(scope: CoroutineScope, design: MainDesign, scheduleRefresh: (force: Boolean) -> Unit) {
        scope.launch {
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
                    activity.resolveStatusSnapshot().serviceRunning
                }
                Remote.broadcasts.clashRunning = runningNow
                if (runningNow) {
                    dashboard.suppressRunningReviveUntil = SystemClock.uptimeMillis() + 8000L
                    activity.stopClashService()
                    design.setClashRunning(false)
                    design.setTunnelStarting(false)
                } else {
                    // User is (re)starting — drop any stale stop latch.
                    dashboard.suppressRunningReviveUntil = 0L
                    if (!bypass.maybePromptRuBypass()) {
                        return@launch
                    }
                    design.setTunnelStarting(true)
                    try {
                        design.startClash()
                    } finally {
                        scheduleRefresh(false)
                    }
                }
            } finally {
                // ClickGuard owns the cooldown; failures must release this operation lock too.
                isToggleStatusInFlight = false
                scheduleRefresh(true)
            }
        }
    }

    /**
     * [com.github.kr328.clash.service.clash.module.ConfigurationModule] runs
     * [Clash.patchSelector] in applyPostLoad then sends PROFILE_LOADED, which increments
     * [profileLoadEpoch]. The JNI snapshot can still show the YAML-default `now` right after
     * [waitForProxyEngineReady]; we wait for that post-load hop before the first dashboard fetch.
     */
    private suspend fun waitForProfileLoadEpochAfter(snapshot: Int, timeoutMs: Long = 3000L) {
        if (profileLoadEpoch.get() > snapshot) return
        withTimeoutOrNull(timeoutMs) {
            while (profileLoadEpoch.get() <= snapshot) {
                delay(15L)
            }
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = prepareActiveProfileForStart()

        if (active == null) return

        val loadEpochSnapshot = profileLoadEpoch.get()
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
                    probe.waitForProxyEngineReady()
                    waitForProfileLoadEpochAfter(loadEpochSnapshot)
                    d?.let { dashboard.fetch(it) }
                    // O-07: warm up ONLY the group the user last looked at (+ its nested auto
                    // subtree), not every auto group. The old blanket warmup url-tested 200-node
                    // groups on connect and saturated the engine ("infinite ping" + sluggish
                    // switch). Other groups warm lazily when opened (see visibleGroupChanged).
                    probe.clearWarmedGroups()
                    val warmed = probe.warmUpGroupSubtree(uiStore.proxyLastGroup)
                    d?.let { dashboard.fetch(it) }
                    // Force-push fresh delays into the UI immediately instead
                    // of waiting for the next dashboard ticker (interactive=2s,
                    // idle=30s). Mihomo health-check writes delays into
                    // proxy.LastDelayForTestUrl synchronously when warmup
                    // finishes; without this re-query the carriage stays blank
                    // until the timer rolls over. (A delay event channel would
                    // side-step this whole class of staleness — see
                    // docs/path-b-engine-parsing.md follow-ups.)
                    if (warmed.isNotEmpty()) {
                        val patches = probe.refreshRuntimeGroupDetails(warmed)
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
                    imports.showHomeImportSheet(this@prepareActiveProfileForStart)
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
