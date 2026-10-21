package com.github.kr328.clash.design.main

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.motion.bindSubscriptionUpdateButton
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.BottomSheetSubscriptionBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.ClickGuard
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.R as MaterialR
import java.util.UUID

/**
 * The subscription details bottom sheet of the Profiles tab. It re-renders from the live profile
 * list and the tab adapter's update state; everything that leaves the sheet (menu, activation,
 * edit, server picker, update) goes through the callbacks.
 */
internal class SubscriptionDetailsSheet(
    private val context: Context,
    private val adapter: ProfileAdapter,
    private val clickGuard: ClickGuard<Pair<UUID, Int>>,
    private val profiles: () -> List<Profile>,
    private val isLoadingServers: (UUID) -> Boolean,
    private val onOpened: (Profile) -> Unit,
    private val onDismissed: (UUID) -> Unit,
    private val onMenu: (Profile, View) -> Unit,
    private val onActivate: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onPickServer: (Profile) -> Unit,
    private val onUpdate: (Profile) -> Unit,
) {
    private var detailsDialog: AppBottomSheetDialog? = null
    private var detailsBinding: BottomSheetSubscriptionBinding? = null
    private var detailsUuid: UUID? = null
    private var activatingUuid: UUID? = null

    fun show(profile: Profile) {
        if (detailsDialog?.isShowing == true) return
        val sheet = BottomSheetSubscriptionBinding.inflate(context.layoutInflater)
        val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
        detailsBinding = sheet
        detailsUuid = profile.uuid
        detailsDialog = dialog
        onOpened(profile)
        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener {
            onDismissed(profile.uuid)
            detailsBinding = null
            detailsUuid = null
            detailsDialog = null
        }
        refresh()
        dialog.show()
    }

    fun refresh() {
        val sheet = detailsBinding ?: return
        val profile = profiles().firstOrNull { it.uuid == detailsUuid }
        if (profile == null) {
            detailsDialog?.dismiss()
            return
        }
        val updating = adapter.states.isUpdating(profile.uuid)
        val failed = adapter.states.hasUpdateError(profile.uuid)
        val activating = activatingUuid == profile.uuid
        sheet.detailName.text = profile.name
        sheet.detailStatus.setText(if (profile.active) R.string.subscription_in_use else R.string.subscription_not_in_use)
        sheet.detailUsage.text = if (profile.imported) adapter.formatUsageLine(context, profile)
            else context.getString(R.string.subscription_not_ready)
        val hasLimit = profile.imported && profile.total >= 2L
        sheet.detailUsageProgress.visibility = if (hasLimit) View.VISIBLE else View.GONE
        sheet.detailUsageProgress.progress =
            (((profile.upload + profile.download).toDouble() / profile.total.coerceAtLeast(1L))
                .coerceIn(0.0, 1.0) * 1000).toInt()
        sheet.detailExpiry.visibility = if (profile.expire > 0L) View.VISIBLE else View.GONE
        sheet.detailExpiry.text = if (profile.expire > 0L) {
            context.getString(R.string.subscription_valid_until,
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(profile.expire)))
        } else ""
        sheet.detailExpiry.setTextColor(context.resolveThemedColor(
            if (profile.expire > 0L && profile.expire <= System.currentTimeMillis()) MaterialR.attr.colorError
            else MaterialR.attr.colorOnSurfaceVariant,
        ))
        sheet.detailUpdated.text = if (profile.updatedAt > 0L) {
            context.getString(R.string.subscription_updated_at,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(profile.updatedAt)))
        } else context.getString(R.string.subscription_never_updated)
        sheet.detailAutoUpdate.text = if (profile.interval > 0L) {
            context.getString(R.string.subscription_auto_update_interval, profile.interval / 60_000L)
        } else context.getString(R.string.subscription_auto_update_off)
        sheet.detailAutoUpdate.visibility = if (profile.type == Profile.Type.File) View.GONE else View.VISIBLE
        sheet.detailMenu.setOnClickListener {
            if (clickGuard.accept(profile.uuid to it.id)) onMenu(profile, it)
        }
        sheet.detailUse.visibility = if (profile.active) View.GONE else View.VISIBLE
        sheet.detailUse.isEnabled = !profile.active && !activating
        sheet.detailUse.setText(when {
            activating -> R.string.subscription_activating
            profile.active -> R.string.subscription_in_use
            !profile.imported -> R.string.subscription_finish_setup
            else -> R.string.profile_use
        })
        sheet.detailUse.setOnClickListener {
            if (!clickGuard.accept(profile.uuid to it.id)) return@setOnClickListener
            if (profile.imported) {
                setActivating(profile.uuid)
                onActivate(profile)
            } else {
                detailsDialog?.dismiss()
                onEdit(profile)
            }
        }
        val loadingServers = isLoadingServers(profile.uuid)
        sheet.detailServers.isEnabled = profile.imported && !loadingServers
        sheet.detailServers.setOnClickListener { onPickServer(profile) }
        sheet.detailServerSummary.text = if (loadingServers) {
            context.getString(R.string.subscription_loading_servers)
        } else adapter.activeNodeDisplayName(profile)
            ?: context.getString(R.string.subscription_server_hint)
        sheet.detailUpdate.visibility = if (profile.imported && profile.type == Profile.Type.Url) View.VISIBLE else View.GONE
        bindSubscriptionUpdateButton(sheet.detailUpdate, adapter.states, profile.uuid)
        sheet.detailUpdate.setOnClickListener { onUpdate(profile) }
        sheet.detailUpdateStatus.visibility = if (failed) View.VISIBLE else View.GONE
        sheet.detailUpdateStatus.setText(R.string.subscription_update_error)
        sheet.detailUpdateStatus.setTextColor(context.resolveThemedColor(MaterialR.attr.colorError))
    }

    fun setActivating(uuid: UUID?) {
        activatingUuid = uuid
        refresh()
    }
}
