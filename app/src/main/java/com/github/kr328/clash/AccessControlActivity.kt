package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.BypassPreset
import com.github.kr328.clash.util.BypassPresets
import com.github.kr328.clash.util.showBypassPresetSheet
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AccessControlActivity : BaseActivity<AccessControlDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            service.accessControlPackages.toMutableSet()
        }
        val initialSelected = selected.toSet()
        var currentMode: AccessControlMode = withContext(Dispatchers.IO) {
            service.accessControlMode
        }
        val initialMode = currentMode

        defer {
            withContext(Dispatchers.IO) {
                val locallyModified =
                    selected.toSet() != initialSelected || currentMode != initialMode
                if (!locallyModified) return@withContext

                val latestPackages = service.accessControlPackages
                val latestMode = service.accessControlMode
                val externallyModified =
                    latestPackages != initialSelected || latestMode != initialMode
                if (externallyModified) {
                    Log.w("Skip access-control save due to external concurrent update")
                    return@withContext
                }

                val changedPackages = selected.toSet() != latestPackages
                val changedMode = currentMode != latestMode
                service.accessControlPackages = selected
                service.accessControlMode = currentMode
                if (changedPackages || changedMode) {
                    service.bypassPresetSeeded = true
                }
                if (clashRunning && (changedPackages || changedMode)) {
                    stopClashService()
                    while (clashRunning) {
                        delay(200)
                    }
                    startClashService()
                }
            }
        }

        val design = AccessControlDesign(this, uiStore, selected)

        setContentDesign(design)

        design.setMode(currentMode)
        design.requests.send(AccessControlDesign.Request.ReloadApps)

        // Merges the preset into the on-screen selection only; persist + VPN
        // restart ride the regular defer-save above, exactly like manual edits.
        suspend fun applyPreset(preset: BypassPreset) {
            val added = withContext(Dispatchers.IO) {
                val before = selected.size
                selected.addAll(preset.installed(packageManager))
                selected.size - before
            }
            currentMode = AccessControlMode.DenySelected
            design.setMode(currentMode)
            design.patchApps(loadApps(selected))
            Toast.makeText(
                this@AccessControlActivity,
                getString(R.string.bypass_preset_seeded, added),
                Toast.LENGTH_SHORT
            ).show()
        }

        maybePromptBypassPreset(service) { applyPreset(it) }

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        AccessControlDesign.Request.ReloadApps -> {
                            design.patchApps(loadApps(selected))
                        }

                        AccessControlDesign.Request.ChangeMode -> {
                            design.pendingMode?.let { mode ->
                                currentMode = mode
                                design.setMode(currentMode)
                                design.patchApps(loadApps(selected))
                            }
                        }

                        AccessControlDesign.Request.SelectAll -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName)
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectNone -> {
                            selected.clear()

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectInvert -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName).toSet() - selected
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            val data = clipboard?.primaryClip

                            if (data != null && data.itemCount > 0) {
                                val packages = data.getItemAt(0).text.split("\n").toSet()
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                selected.clear()
                                selected.addAll(all)
                            }

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Export -> {
                            val clipboard = getSystemService<ClipboardManager>()

                            val data = ClipData.newPlainText(
                                "packages",
                                selected.joinToString("\n")
                            )

                            clipboard?.setPrimaryClip(data)
                        }

                        AccessControlDesign.Request.ApplyPreset -> {
                            showPresetPicker(design) { applyPreset(it) }
                        }
                    }
                }
            }
        }
    }

    /**
     * One-time offer on first visit: suggest the preset with the most
     * installed matches. Installed apps beat SIM/locale as a region signal,
     * and never auto-apply — an emigrant may want banks inside the tunnel.
     */
    private suspend fun maybePromptBypassPreset(
        service: ServiceStore,
        apply: suspend (BypassPreset) -> Unit,
    ) {
        val best = withContext(Dispatchers.IO) {
            if (service.bypassPresetSeeded || service.accessControlPackages.isNotEmpty()) null
            else BypassPresets.bestInstalled(this@AccessControlActivity, packageManager)
        } ?: return
        val (preset, installedApps) = best
        val title = BypassPresets.displayTitle(this, preset)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.bypass_prompt_title, title))
            .setMessage(getString(R.string.bypass_prompt_message, installedApps.size, title))
            .setPositiveButton(R.string.bypass_prompt_apply) { _, _ ->
                launch { apply(preset) }
            }
            .setNegativeButton(R.string.bypass_preset_later, null)
            .show()
    }

    private suspend fun showPresetPicker(
        design: AccessControlDesign,
        apply: suspend (BypassPreset) -> Unit,
    ) {
        val presets = withContext(Dispatchers.IO) {
            BypassPresets.load(this@AccessControlActivity)
                .map { it to it.installed(packageManager).size }
                .sortedByDescending { it.second }
        }
        if (presets.isEmpty()) return

        showBypassPresetSheet(design, presets) { preset ->
            launch { apply(preset) }
        }
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort
            val systemApp = uiStore.accessControlSystemApp

            val base = compareByDescending<AppInfo> { it.packageName in selected }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true || it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                }
                .filter {
                    systemApp || !it.isSystemApp
                }
                .map {
                    it.toAppInfo(pm)
                }
                .sortedWith(comparator)
                .toList()
        }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }
}
