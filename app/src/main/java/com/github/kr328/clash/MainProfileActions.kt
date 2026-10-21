package com.github.kr328.clash

import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.util.ShareImportSupport
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.companion.controller.ControllerStore
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.commitProfileWithProgress
import com.github.kr328.clash.util.createEmptyUrlProfileAndOpenEditor
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.showProfileQuickEditSheet
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Profile actions launched from [MainActivity]'s Home and Profiles tabs: the import sheet
 * (clipboard / URL / QR / file), subscription import with background name upgrade, the
 * per-profile overflow menu, "Update all", the proxy YAML viewer and opening the rules hub.
 */
internal class MainProfileActions(private val activity: MainActivity) {
    private val runtime: MainProxyRuntime
        get() = activity.proxyRuntime

    private val dashboard: MainDashboard
        get() = activity.dashboard

    private val themedContext
        get() = activity.themedContext

    internal fun showHomeImportSheet(design: MainDesign) {
        // design.context, not the activity: the brand accent lives in the design's themed wrapper.
        val dialog = AppBottomSheetDialog(design.context, fitContentHeight = false)
        val view = design.context.layoutInflater.inflate(R.layout.bottom_sheet_home_import, null)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.opt_clipboard).setOnClickListener {
            dialog.dismiss()
            activity.launch { importFromClipboard(design) }
        }
        view.findViewById<View>(R.id.opt_url).setOnClickListener {
            dialog.dismiss()
            activity.launch { activity.createEmptyUrlProfileAndOpenEditor() }
        }
        view.findViewById<View>(R.id.opt_qr).setOnClickListener {
            dialog.dismiss()
            activity.scanLauncher.launch(null)
        }
        view.findViewById<View>(R.id.opt_file).setOnClickListener {
            dialog.dismiss()
            activity.launch { importFromFile(design) }
        }
        // Companion "control a TV" entry — phones only; on TV it lives on the home screen.
        view.findViewById<View>(R.id.opt_companion).also { row ->
            row.visibility = if (activity.isTelevision()) View.GONE else View.VISIBLE
            row.setOnClickListener {
                dialog.dismiss()
                // Straight to the remote: one paired TV opens it, several open the list, none
                // opens the remote-control screen to pair the first one.
                val agents = ControllerStore(activity).list()
                activity.startActivity(
                    when (agents.size) {
                        0 -> CompanionActivity::class.intent
                        1 -> CompanionRemoteActivity::class.intent
                            .putExtra(CompanionRemoteActivity.EXTRA_DEVICE_ID, agents.single().deviceId)
                        else -> CompanionControlActivity::class.intent
                    },
                )
            }
        }
        dialog.show()
    }

    /**
     * Pick a local YAML, create a [Profile.Type.File] profile, write the picked
     * content straight into its `config.yaml` and commit — then activate it,
     * same as a URL/clipboard import. Falls back to Properties if the engine
     * rejects the file (invalid YAML), so the user can fix it.
     */
    private suspend fun importFromFile(design: MainDesign) {
        val uri: Uri = activity.startActivityForResult(
            ActivityResultContracts.GetContent(),
            "*/*",
        ) ?: return

        val name = uri.fileName?.substringBeforeLast('.')?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.new_profile)

        val uuid = withProfile { create(Profile.Type.File, name) }

        try {
            FilesClient(activity).copyDocument("$uuid/config.yaml", uri)
            themedContext.commitProfileWithProgress(uuid)
        } catch (e: Exception) {
            showImportCommitFailureDialog(uuid, e)
            return
        }

        val profile = withProfile { queryByUUID(uuid) }
        if (profile?.imported == true) {
            withProfile { setActive(profile) }
            runtime.ensureGlobalSelectionSafeWithRetry()
            design.showToast(activity.getString(R.string.import_done_named, name), ToastDuration.Long)
        } else {
            launchProperties(uuid)
        }
        with(dashboard) { design.fetch() }
    }

    internal fun showProfileOverflowMenu(design: MainDesign, profile: Profile, anchor: View) {
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
                                runtime.ensureGlobalSelectionSafeWithRetry()
                            } else {
                                design.requestSave(profile)
                            }
                        }
                        with(dashboard) { design.fetch() }
                    }
                    true
                }
                R.id.profile_menu_edit -> {
                    activity.showProfileQuickEditSheet(design, profile) { with(dashboard) { design.fetch() } }
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
                                with(dashboard) { design.fetch() }
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

    internal suspend fun importFromClipboard(design: MainDesign) {
        val text = withContext(Dispatchers.Main) {
            activity.getSystemService<ClipboardManager>()?.primaryClip
                ?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        }
        if (text.isEmpty()) {
            design.showToast(R.string.clipboard_empty, ToastDuration.Short)
            return
        }
        if (!ShareImportSupport.isAllowedUrlProfileSource(text)) {
            design.showToast(R.string.clipboard_no_url, ToastDuration.Long)
            return
        }
        importSubscriptionFromUrl(design, text)
    }

    /**
     * Resolves a friendly name from the subscription HTTP response, creates the profile,
     * [commit]s the fetch, activates it — no Properties screen on success.
     */
    internal suspend fun importSubscriptionFromUrl(design: MainDesign, url: String) {
        // Optimistic import: don't block the UI on Profile-Title lookup.
        // Create with a host-based preliminary name (instant), kick off the
        // network guess in the background, and rename after commit() if a
        // better name arrives. Previous flow froze the UI for up to 8s before
        // the progress bar even appeared.
        val pending = com.github.kr328.clash.util.AsyncNameResolver.start(activity, activity, url)
        val uuid = withProfile {
            create(Profile.Type.Url, pending.preliminaryName, url)
        }
        try {
            themedContext.commitProfileWithProgress(uuid)
        } catch (e: Exception) {
            pending.betterName.cancel()
            showImportCommitFailureDialog(uuid, e)
            return
        }
        val displayName = upgradeProfileNameIfBetter(uuid, pending)
        val profile = withProfile { queryByUUID(uuid) }
        if (profile?.imported == true) {
            withProfile { setActive(profile) }
            runtime.ensureGlobalSelectionSafeWithRetry()
            design.showToast(activity.getString(R.string.import_done_named, displayName), ToastDuration.Long)
        } else {
            launchProperties(uuid)
        }
        with(dashboard) { design.fetch() }
    }

    /**
     * Awaits the background [com.github.kr328.clash.util.AsyncNameResolver]
     * result and renames the imported profile if a better name arrived.
     * Uses [com.github.kr328.clash.service.remote.IProfileManager.renameImported]
     * (direct ImportedDao update) — NOT [patch], which would put the profile
     * back into a pending/draft state and surface as "unsaved" to the user.
     * Returns the effective display name (better one if applied, preliminary
     * otherwise).
     */
    private suspend fun upgradeProfileNameIfBetter(
        uuid: java.util.UUID,
        pending: com.github.kr328.clash.util.AsyncNameResolver.Pending,
    ): String {
        val better = runCatching { pending.betterName.await() }.getOrNull()
            ?: return pending.preliminaryName
        runCatching { withProfile { renameImported(uuid, better) } }
        return better
    }

    private suspend fun launchProperties(uuid: UUID) {
        activity.startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            PropertiesActivity::class.intent.setUUID(uuid)
        )
    }

    private suspend fun showImportCommitFailureDialog(uuid: UUID, e: Throwable) {
        withContext(Dispatchers.Main) {
            val raw = e.message?.trim().orEmpty().ifBlank { e.javaClass.simpleName }
            val msg = if (raw.length > 6000) raw.take(6000) + "…" else raw
            MaterialAlertDialogBuilder(themedContext)
                .setTitle(activity.getString(R.string.import_failed_title))
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(activity.getString(R.string.import_failed_open_editor)) { _, _ ->
                    activity.startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
                .show()
        }
    }

    internal suspend fun showProxyYamlDialog(title: String, body: String) {
        withContext(Dispatchers.Main) {
            val themed = themedContext
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            val scroll = ScrollView(themed)
            val tv = TextView(themed).apply {
                text = body
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

    internal suspend fun updateAllProfiles(design: MainDesign) {
        // Per-profile guard: one subscription failing (network /
        // bad config / commit) must NOT take down the whole
        // "Update all" with an uncaught coroutine exception.
        var failed = 0
        withProfile {
            queryAll().forEach { p ->
                if (p.imported && p.type != Profile.Type.File) {
                    runCatching { update(p.uuid) }.onFailure { failed++ }
                }
            }
        }
        if (failed > 0) {
            design.showToast(
                activity.getString(R.string.profiles_update_all_failed, failed),
                ToastDuration.Long,
            )
        }
        with(dashboard) { design.fetch() }
    }

    internal suspend fun openRulesHub() {
        val uuid = withProfile { queryActive()?.takeIf { it.imported }?.uuid }
        val hubIntent = uuid?.let { RulesHubActivity::class.intent.setUUID(it) }
            ?: RulesHubActivity::class.intent
        activity.startActivity(hubIntent)
    }
}
