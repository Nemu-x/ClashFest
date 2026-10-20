package com.github.kr328.clash

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.AboutVersions
import com.github.kr328.clash.util.AppUpdateChecker
import com.github.kr328.clash.util.GitHubReleaseUpdate
import com.github.kr328.clash.util.UpdateApkVerifier
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-update flow for [MainActivity]: the About sheet's "Check updates" action, the Home-header
 * update badge and dialog, and the DownloadManager-backed APK download, verification and install
 * hand-off (including the pending-download marker that survives the unknown-sources detour).
 */
internal class MainAppUpdates(private val activity: MainActivity) {
    private val themedContext
        get() = activity.themedContext

    internal var isCheckingUpdates: Boolean = false
    private var pendingApkDownloadId: Long = -1L
    internal var downloadReceiverRegistered: Boolean = false
    private val updatePrefs by lazy { activity.getSharedPreferences("app_update", Context.MODE_PRIVATE) }

    private fun clearPendingDownloadState() {
        pendingApkDownloadId = -1L
        updatePrefs.edit().remove("pending_download_id").apply()
    }

    internal val apkDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id <= 0L || id != pendingApkDownloadId) return
            clearPendingDownloadState()

            checkDownloadedApkAndInstall(id)
        }
    }

    internal fun CoroutineScope.bindOpenAbout(design: MainDesign) {
        design.onOpenAbout = {
            launch {
                // Store builds (F-Droid) ship without the GitHub updater: no status
                // line and no "Check updates" button.
                val onCheckUpdates: (((Boolean) -> Unit, (String?) -> Unit) -> Unit)? =
                    if (!BuildConfig.SELF_UPDATE) {
                        null
                    } else {
                        { setLoading, setStatus ->
                            if (!isCheckingUpdates) {
                                launch {
                                    isCheckingUpdates = true
                                    setStatus(null)
                                    setLoading(true)
                                    try {
                                        checkForUpdates(design, setStatus)
                                    } finally {
                                        isCheckingUpdates = false
                                        setLoading(false)
                                    }
                                }
                            }
                        }
                    }
                design.showAbout(
                    versionName = AboutVersions.app,
                    coreVersion = AboutVersions.core,
                    initialUpdateStatus = if (!BuildConfig.SELF_UPDATE) {
                        null
                    } else {
                        AppUpdateChecker.peekCachedRelease(activity)
                            ?.let { activity.getString(R.string.about_update_available, it.tagName) }
                    },
                    onCheckUpdates = onCheckUpdates,
                )
            }
        }
    }

    private suspend fun checkForUpdates(design: MainDesign, setStatus: (String?) -> Unit) {
        val latest = GitHubReleaseUpdate.fetchLatest()
        if (latest == null) {
            setStatus(activity.getString(R.string.about_update_check_failed))
            return
        }
        val current = withContext(Dispatchers.IO) {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
        }
        val hasUpdate = GitHubReleaseUpdate.compareVersions(latest.tagName, current) > 0
        withContext(Dispatchers.Main) {
            if (!hasUpdate) {
                setStatus(activity.getString(R.string.about_update_latest))
                return@withContext
            }
            setStatus(activity.getString(R.string.about_update_available, latest.tagName))
            // Connected actions: cache the release so the Home-header badge lights up
            // immediately and stays even if the user taps "Later", then show the same in-app
            // update dialog the Home icon uses — no system notification on the manual path.
            AppUpdateChecker.cacheRelease(activity, latest)
            refreshUpdateBadge(design)
            showUpdateAvailableDialog()
        }
    }

    private fun checkDownloadedApkAndInstall(downloadId: Long) {
        val dm = activity.getSystemService<DownloadManager>() ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> installDownloadedApk(dm, downloadId)
                DownloadManager.STATUS_FAILED -> {
                    clearPendingDownloadState()
                    Toast.makeText(activity, R.string.about_download_failed, Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
    }

    private fun installDownloadedApk(dm: DownloadManager, downloadId: Long) {
        val uri = dm.getUriForDownloadedFile(downloadId)
        if (uri == null) {
            Toast.makeText(activity, R.string.about_download_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            if (!UpdateApkVerifier.isTrustedDownloadedApk(activity, localUri)) {
                clearPendingDownloadState()
                Toast.makeText(activity, R.string.about_download_failed, Toast.LENGTH_SHORT).show()
                return
            }
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
            Toast.makeText(activity, R.string.about_enable_unknown_apps, Toast.LENGTH_LONG).show()
            updatePrefs.edit().putLong("pending_download_id", downloadId).apply()
            return
        }

        // Clear pending marker before opening installer to avoid install loop
        // if user cancels installation and returns to the app.
        clearPendingDownloadState()
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Toast.makeText(activity, R.string.about_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    internal fun syncPendingApkDownload() {
        if (!BuildConfig.SELF_UPDATE) return
        val pending = updatePrefs.getLong("pending_download_id", -1L)
        if (pending > 0L) {
            pendingApkDownloadId = pending
            checkDownloadedApkAndInstall(pending)
        }
    }

    /**
     * Read the cached release snapshot and reflect it in the header badge. Also kicks off
     * a throttled background fetch so a release published since the last AlarmManager tick
     * surfaces while the activity is in the foreground.
     */
    internal fun refreshUpdateBadge(design: MainDesign) {
        if (!BuildConfig.SELF_UPDATE) {
            design.setUpdateBadgeVisible(false)
            return
        }
        design.setUpdateBadgeVisible(AppUpdateChecker.isUpdateAvailable(activity))
        activity.launch {
            runCatching { AppUpdateChecker.maybeOpportunisticCheck(activity) }
            design.setUpdateBadgeVisible(AppUpdateChecker.isUpdateAvailable(activity))
        }
    }

    /**
     * In-app counterpart of the system update notification. Reads the cached release from
     * SharedPreferences so it opens instantly; the Download & install button feeds into the
     * same verified download helper the notification action uses.
     */
    internal fun showUpdateAvailableDialog() {
        val info = AppUpdateChecker.peekCachedRelease(activity) ?: return
        val body = info.body.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.update_dialog_message_fallback)
        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle(activity.getString(R.string.update_dialog_title_fmt, info.tagName))
            .setMessage(body)
            .setNegativeButton(R.string.update_dialog_button_later, null)
        if (!info.apkUrl.isNullOrBlank()) {
            dialog.setPositiveButton(R.string.update_dialog_button_download) { _, _ ->
                AppUpdateChecker.dismissUpdateNotification(activity)
                runCatching {
                    val id = GitHubReleaseUpdate.enqueueApkDownload(
                        context = activity,
                        tagName = info.tagName.ifBlank { "update" },
                        apkUrl = info.apkUrl,
                        apkName = info.apkName,
                    )
                    if (id > 0L) {
                        pendingApkDownloadId = id
                        updatePrefs.edit().putLong("pending_download_id", id).apply()
                        Toast.makeText(activity, R.string.about_download_started, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(activity, R.string.about_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } else if (info.htmlUrl.isNotBlank()) {
            // No prebuilt APK on this release — fall back to opening the release page.
            dialog.setPositiveButton(R.string.about_open_release) { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                }
            }
        }
        dialog.show()
    }
}
