package com.github.kr328.clash.main

import android.content.ClipboardManager
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import com.github.kr328.clash.CompanionActivity
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.PropertiesActivity
import com.github.kr328.clash.common.util.ShareImportSupport
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.qr.QrScanResult
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.commitProfileWithProgress
import com.github.kr328.clash.util.createEmptyUrlProfileAndOpenEditor
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every way a subscription enters the app from Home: the import sheet, clipboard, QR scan,
 * a local YAML file and the shared URL path they funnel into.
 */
internal class MainImportFlows(
    private val activity: MainActivity,
    private val tunnel: MainTunnelModeController,
    private val dashboard: MainDashboardFetcher,
    private val launchQrScan: () -> Unit,
) {
    private val design get() = activity.currentDesign
    private val themedContext get() = activity.themedContext

    fun showHomeImportSheet(design: MainDesign) {
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
            launchQrScan()
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
                activity.startActivity(CompanionActivity::class.intent)
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
            tunnel.ensureGlobalSelectionSafeWithRetry()
            design.showToast(activity.getString(R.string.import_done_named, name), ToastDuration.Long)
        } else {
            launchProperties(uuid)
        }
        dashboard.fetch(design)
    }

    fun onScanResult(result: QrScanResult) {
        activity.launch {
            val d = design ?: return@launch
            when (result) {
                is QrScanResult.Success -> {
                    val url = result.content
                    if (url.isNotBlank()) {
                        importSubscriptionFromUrl(d, url)
                    }
                }

                QrScanResult.Canceled -> Unit
                QrScanResult.MissingPermission ->
                    d.showExceptionToast(activity.getString(R.string.import_from_qr_no_permission))

                is QrScanResult.Error ->
                    d.showExceptionToast(activity.getString(R.string.import_from_qr_exception))
            }
        }
    }

    suspend fun importFromClipboard(design: MainDesign) {
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
    private suspend fun importSubscriptionFromUrl(design: MainDesign, url: String) {
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
            tunnel.ensureGlobalSelectionSafeWithRetry()
            design.showToast(activity.getString(R.string.import_done_named, displayName), ToastDuration.Long)
        } else {
            launchProperties(uuid)
        }
        dashboard.fetch(design)
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
}
