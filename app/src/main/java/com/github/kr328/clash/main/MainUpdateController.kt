package com.github.kr328.clash.main

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.common.util.intent
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
 * App self-update: the About-sheet check, the Home-header badge, the in-app update dialog and
 * the DownloadManager completion receiver that verifies and installs the APK.
 */
internal class MainUpdateController(private val activity: MainActivity) {
    private val themedContext get() = activity.themedContext

    private var isCheckingUpdates: Boolean = false
    private var pendingApkDownloadId: Long = -1L
    private var downloadReceiverRegistered: Boolean = false
    private val updatePrefs by lazy { activity.getSharedPreferences("app_update", Context.MODE_PRIVATE) }

    /** Cleared with the run's scope: its launched children (and their `finally`) die on a soft recreate. */
    fun resetForNewRun() {
        isCheckingUpdates = false
    }

    private fun clearPendingDownloadState() {
        pendingApkDownloadId = -1L
        updatePrefs.edit().remove("pending_download_id").apply()
    }

    private val apkDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id <= 0L || id != pendingApkDownloadId) return
            clearPendingDownloadState()

            checkDownloadedApkAndInstall(id)
        }
    }

    fun onCreate() {
        if (!downloadReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(
                    apkDownloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                activity.registerReceiver(apkDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            downloadReceiverRegistered = true
        }

        // If the installed version is already at/ahead of whatever we last notified about,
        // clear the stale "update available" banner so users don't see it after upgrading.
        runCatching {
            val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
            AppUpdateChecker.resetIfUpdated(activity, current)
        }
    }

    fun onDestroy() {
        if (downloadReceiverRegistered) {
            runCatching { activity.unregisterReceiver(apkDownloadReceiver) }
            downloadReceiverRegistered = false
        }
    }

    /** About sheet: shows the versions and runs the manual update check on request. */
    fun bindAbout(scope: CoroutineScope, design: MainDesign) {
        design.onOpenAbout = {
            scope.launch {
                design.showAbout(
                    versionName = AboutVersions.app,
                    coreVersion = AboutVersions.core,
                    initialUpdateStatus = AppUpdateChecker.peekCachedRelease(activity)
                        ?.let { activity.getString(R.string.about_update_available, it.tagName) },
                ) { setLoading, setStatus ->
                    if (isCheckingUpdates) return@showAbout
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

    fun syncPendingApkDownload() {
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
    fun refreshUpdateBadge(design: MainDesign) {
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
    fun showUpdateAvailableDialog() {
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
