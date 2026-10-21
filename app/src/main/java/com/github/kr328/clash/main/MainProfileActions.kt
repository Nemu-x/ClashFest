package com.github.kr328.clash.main

import android.graphics.Typeface
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.ConfigScriptActivity
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.ProfileConfigActivity
import com.github.kr328.clash.PropertiesActivity
import com.github.kr328.clash.ProxyProvidersEditorActivity
import com.github.kr328.clash.RulesHubActivity
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ImportRetry
import com.github.kr328.clash.util.ProfileRuntimeTarget
import com.github.kr328.clash.util.closeConnectionsAfterUserProxySwitchIfEnabled
import com.github.kr328.clash.util.showProfileQuickEditSheet
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Actions on a subscription from the Home list: the overflow menu, activation, forced and
 * bulk updates, selecting a node, the proxy-YAML dialog and the toasts that report an
 * update's outcome.
 */
internal class MainProfileActions(
    private val activity: MainActivity,
    private val tunnel: MainTunnelModeController,
    private val dashboard: MainDashboardFetcher,
    private val metadata: MainSubscriptionMetadata,
) {
    private val uiStore get() = activity.store
    private val design get() = activity.currentDesign
    private val themedContext get() = activity.themedContext

    fun showProfileOverflowMenu(design: MainDesign, profile: Profile, anchor: View) {
        // anchor.context is the design's themed wrapper (the row was inflated from it).
        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.menu_profile_home, popup.menu)
        val m = popup.menu
        m.findItem(R.id.profile_menu_set_active).isVisible =
            profile.imported && !profile.active
        m.findItem(R.id.profile_menu_subscription_sources).isVisible = profile.imported
        m.findItem(R.id.profile_menu_rules).isVisible = profile.imported
        m.findItem(R.id.profile_menu_duplicate).isVisible = profile.imported
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.profile_menu_set_active -> {
                    activity.launch {
                        withProfile {
                            if (profile.imported) {
                                setActive(profile)
                                tunnel.ensureGlobalSelectionSafeWithRetry()
                            } else {
                                design.requestSave(profile)
                            }
                        }
                        dashboard.fetch(design)
                    }
                    true
                }
                R.id.profile_menu_edit -> {
                    activity.showProfileQuickEditSheet(design, profile) { dashboard.fetch(design) }
                    true
                }
                R.id.profile_menu_subscription_sources -> {
                    if (profile.imported) {
                        activity.startActivity(ProxyProvidersEditorActivity::class.intent.setUUID(profile.uuid))
                    }
                    true
                }
                R.id.profile_menu_rules -> {
                    if (profile.imported) {
                        activity.startActivity(RulesHubActivity::class.intent.setUUID(profile.uuid))
                    }
                    true
                }
                R.id.profile_menu_view_config -> {
                    activity.startActivity(ProfileConfigActivity::class.intent.setUUID(profile.uuid))
                    true
                }
                R.id.profile_menu_config_script -> {
                    if (profile.imported) {
                        activity.startActivity(ConfigScriptActivity::class.intent.setUUID(profile.uuid))
                    }
                    true
                }
                R.id.profile_menu_duplicate -> {
                    activity.launch {
                        val uuid = withProfile { clone(profile.uuid) }
                        activity.startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                    }
                    true
                }
                R.id.profile_menu_delete -> {
                    MaterialAlertDialogBuilder(design.context)
                        .setTitle(R.string.delete)
                        .setMessage(R.string.profile_delete_confirm)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            activity.launch {
                                withProfile { delete(profile.uuid) }
                                dashboard.fetch(design)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    suspend fun showProxyYamlDialog(title: String, body: String) {
        val safeBody = com.github.kr328.clash.service.util.XDriveSecretRedactor.redactEntryYaml(body)
        withContext(Dispatchers.Main) {
            val themed = themedContext
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            val scroll = ScrollView(themed)
            val tv = TextView(themed).apply {
                text = safeBody
                setTextIsSelectable(true)
                setPadding(pad, pad, pad, pad)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
            scroll.addView(tv)
            MaterialAlertDialogBuilder(themed)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Taps a node in the Home list: switch the selector in the engine, or remember it for the next start. */
    fun patchHomeProxy(
        scope: CoroutineScope,
        design: MainDesign,
        profile: Profile,
        group: String,
        name: String,
        scheduleRefresh: () -> Unit,
        scheduleProxyDetails: (Profile, String) -> Unit,
    ): Job = scope.launch {
        try {
            val runningNow = withContext(Dispatchers.IO) {
                activity.resolveStatusSnapshot().serviceRunning
            }
            Remote.broadcasts.clashRunning = runningNow
            val activeUuid = withProfile { queryActive()?.uuid }
            if (!ProfileRuntimeTarget.canUseEngine(runningNow, activeUuid, profile.uuid)) {
                if (isProxyProviderKeyName(name)) {
                    scheduleRefresh()
                    return@launch
                }
                design.markProxySelectionPending(profile, group, name)
                withProfile {
                    rememberProxySelection(profile.uuid, group, name)
                }
                if (activeUuid == profile.uuid) uiStore.proxyLastGroup = group
                scheduleRefresh()
                return@launch
            }
            design.markProxySelectionPending(profile, group, name)
            val patched = withClash {
                patchSelector(group, name)
            }
            if (patched) {
                uiStore.proxyLastGroup = group
                withProfile {
                    rememberProxySelection(profile.uuid, group, name)
                }
                activity.closeConnectionsAfterUserProxySwitchIfEnabled { message, duration ->
                    design.showToast(message, duration)
                }
            } else {
                design.showToast(R.string.proxy_switch_selector_failed, ToastDuration.Long)
            }
            scheduleProxyDetails(profile, group)
        } catch (e: CancellationException) {
            throw e
        }
    }

    fun forceUpdate(scope: CoroutineScope, design: MainDesign, profile: Profile, scheduleRefresh: () -> Unit) {
        scope.launch {
            if (!profile.imported || profile.type != Profile.Type.Url) {
                design.cancelSubscriptionUpdate(profile.uuid)
                design.showToast(R.string.subscription_update_unavailable, ToastDuration.Long)
                return@launch
            }
            var updated = false
            try {
                ImportRetry.withTransientRetry { withProfile { update(profile.uuid) } }
                design.finishSubscriptionUpdate(profile.uuid, true)
                updated = true
                design.showToast(activity.getString(R.string.toast_profile_updated_complete, profile.name), ToastDuration.Long)
            } catch (error: CancellationException) {
                design.cancelSubscriptionUpdate(profile.uuid)
                throw error
            } catch (error: Exception) {
                design.finishSubscriptionUpdate(profile.uuid, false)
                design.showSubscriptionUpdateFailure(profile, error)
            }
            if (updated) launch(Dispatchers.IO) {
                uiStore.subscriptionMetadataLastFetch = 0L
                runCatching { metadata.syncSubscriptionMetadata() }
                withContext(Dispatchers.Main) { metadata.refreshAnnouncement(design) }
            }
            scheduleRefresh()
        }
    }

    fun activate(scope: CoroutineScope, design: MainDesign, profile: Profile, scheduleRefresh: () -> Unit) {
        scope.launch {
            try {
                withProfile {
                    if (profile.imported) {
                        setActive(profile)
                        tunnel.ensureGlobalSelectionSafeWithRetry()
                    } else {
                        design.requestSave(profile)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                design.showExceptionToast(error)
            } finally {
                design.setSubscriptionActivating(null)
                scheduleRefresh()
            }
        }
    }

    fun updateAll(scope: CoroutineScope, design: MainDesign) {
        scope.launch {
            var updated = 0
            var failed = 0
            var eligible = 0
            try {
                for (profile in withProfile { queryAll() }) {
                    if (!profile.imported || profile.type != Profile.Type.Url) continue
                    eligible++
                    if (!design.markSubscriptionUpdateStarted(profile.uuid)) continue
                    try {
                        ImportRetry.withTransientRetry { withProfile { update(profile.uuid) } }
                        design.finishSubscriptionUpdate(profile.uuid, true)
                        updated++
                    } catch (error: CancellationException) {
                        design.cancelSubscriptionUpdate(profile.uuid)
                        throw error
                    } catch (_: Exception) {
                        failed++
                        design.finishSubscriptionUpdate(profile.uuid, false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                design.showToast(R.string.remote_service_unavailable, ToastDuration.Long)
                return@launch
            } finally {
                design.finishAllSubscriptionUpdates()
            }
            val result = when {
                eligible == 0 -> activity.getString(R.string.subscription_update_none)
                updated + failed == 0 -> activity.getString(R.string.subscription_update_already_running)
                else -> activity.getString(R.string.subscription_update_all_result, updated, failed)
            }
            design.showToast(result, ToastDuration.Long)
            dashboard.fetch(design)
        }
    }

    /** Proxy-provider YAML keys (sub1, sub2, …) are not leaf proxy names; never persist as selection. */
    private fun isProxyProviderKeyName(name: String): Boolean =
        name.matches(Regex("^sub\\d+$", RegexOption.IGNORE_CASE))

    fun onProfileUpdateCompleted(uuid: UUID?) {
        if (uuid == null) return
        val announce = design?.recordSubscriptionUpdateResult(uuid, true) == true

        activity.launch {
            val name = withProfile { queryByUUID(uuid)?.name } ?: return@launch
            if (announce) design?.showToast(
                activity.getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
            // Surface the one-shot "our merge broke an otherwise-valid config"
            // marker set by ProfileProcessor (the update still committed).
            if (ServiceStore(activity).consumeUpdateEngineWarning(uuid)) {
                design?.showToast(
                    activity.getString(R.string.profiles_update_engine_warning),
                    ToastDuration.Long,
                )
            }
            // Rules dropped because their proxy/group vanished from the new
            // subscription (the update still succeeded; the rules were dead).
            val orphaned = ServiceStore(activity).consumeOrphanedRulesDropped(uuid)
            if (orphaned.isNotEmpty()) {
                design?.showToast(
                    activity.getString(R.string.profiles_orphaned_rules_dropped, orphaned.size),
                    ToastDuration.Long,
                )
            }
            // Proxy-group members that vanished from the new subscription were pruned so the
            // profile could load (emptied groups were blocked, not sent DIRECT). Tell the user.
            // Nodes on a protocol Citrus removed (VMess, SSR, ...) were skipped so the rest of the
            // subscription loads. Without this the servers would just be missing.
            val skippedLegacy = ServiceStore(activity).consumeRemovedProtocolSkipped(uuid)
            if (skippedLegacy > 0) {
                design?.showToast(
                    activity.getString(R.string.profiles_removed_protocol_skipped, skippedLegacy),
                    ToastDuration.Long,
                )
            }
            val repairedGroups = ServiceStore(activity).consumeProxyGroupsRepaired(uuid)
            if (repairedGroups > 0) {
                design?.showToast(
                    activity.getString(R.string.profiles_proxy_groups_repaired, repairedGroups),
                    ToastDuration.Long,
                )
            }
        }
    }

    fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if (uuid == null || design?.recordSubscriptionUpdateResult(uuid, false) != true) return
        activity.launch {
            val profile = withProfile { queryByUUID(uuid) } ?: return@launch
            design?.showSubscriptionUpdateFailure(profile, IllegalStateException(reason))
        }
    }
}
