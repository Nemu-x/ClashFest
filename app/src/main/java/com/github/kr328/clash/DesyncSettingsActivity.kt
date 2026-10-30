package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.core.model.DesyncCatalog
import com.github.kr328.clash.design.DesyncSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DesyncSettingsForm
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.DesyncOverlayState
import com.github.kr328.clash.service.model.DesyncProfileSettings
import com.github.kr328.clash.service.util.DesyncOverlay
import com.github.kr328.clash.util.commitProfileWithProgress
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Desync-only profiles. Without a UUID: the setup screen of a new profile (services, custom
 * domains, Create), which creates, commits and activates it and returns RESULT_OK. With the UUID of
 * a Desync-only profile: its settings (adds the strategy picker and the device capabilities); Save
 * regenerates the profile, and a running core reloads it. With the UUID of a subscription profile:
 * desync on top of that subscription (an on/off switch, then the same settings); Save stores it in
 * the profile's user layer and re-composes it.
 */
class DesyncSettingsActivity : BaseActivity<DesyncSettingsDesign>() {
    override suspend fun main() {
        val uuid = intent.uuid
        val profile = uuid?.let { withProfile { queryByUUID(it) } }
        if (uuid != null && profile == null) return finish()

        val mode = when {
            uuid == null -> DesyncSettingsDesign.Mode.Setup
            profile?.isDesyncOnly == true -> DesyncSettingsDesign.Mode.Edit
            else -> DesyncSettingsDesign.Mode.Overlay
        }
        title = getString(
            when (mode) {
                DesyncSettingsDesign.Mode.Setup -> R.string.desync_only_title
                DesyncSettingsDesign.Mode.Edit -> R.string.desync_settings
                DesyncSettingsDesign.Mode.Overlay -> R.string.desync_overlay_title
            },
        )
        val overlayState = if (mode == DesyncSettingsDesign.Mode.Overlay && uuid != null) {
            DesyncOverlayState.decode(withProfile { readDesyncOverlay(uuid) }) ?: DesyncOverlayState()
        } else {
            null
        }

        val design = DesyncSettingsDesign(this, mode)
        setContentDesign(design)

        // From the service, never the bridge in this process: see IClashManager.queryDesyncCatalog.
        val loaded = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(DesyncCatalog.serializer(), withClash { queryDesyncCatalog() })
        }.onFailure { Log.w("Desync catalogs unavailable", it) }
        val catalogs = loaded.getOrNull()

        if (catalogs == null) {
            design.bind(DesyncSettingsForm.from(DesyncProfileSettings(), emptyList(), null), emptyList(), null, null)
            val reason = loaded.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
            design.showMessage(getString(R.string.desync_unavailable, reason))
            design.setBusy(true)
        } else {
            val stored = overlayState?.overlay?.settings
                ?: DesyncProfileSettings.decode(profile?.desyncSettings)
                ?: DesyncProfileSettings()
            val form = DesyncSettingsForm.from(stored, catalogs.services, catalogs.presets)
            overlayState?.let { design.overlayEnabled = it.active }
            design.bind(form, catalogs.services, catalogs.presets, catalogs.capabilities)
            if (overlayState?.operator == "deny") {
                design.showMessage(R.string.desync_overlay_denied)
                design.setBusy(true)
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        DesyncSettingsDesign.Request.Submit -> {
                            val form = design.form ?: return@onReceive
                            if (catalogs == null) return@onReceive
                            when {
                                uuid == null -> create(design, form)
                                mode == DesyncSettingsDesign.Mode.Overlay -> saveOverlay(design, uuid, form)
                                else -> save(design, uuid, form)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun create(design: DesyncSettingsDesign, form: DesyncSettingsForm) {
        if (form.isEmpty) {
            design.showMessage(R.string.desync_nothing_selected)
            return
        }
        val json = form.toSettings().encode()
        design.setBusy(true)
        try {
            withProfile { checkDesyncSettings(json) }?.let {
                showFailure(it)
                return
            }
            val uuid = withProfile { createDesyncOnly(getString(R.string.desync_profile_default_name), json) }
            try {
                commitProfileWithProgress(uuid)
            } catch (e: Exception) {
                // A draft nobody can fix by hand (its config is generated): drop it.
                runCatching { withProfile { delete(uuid) } }
                showFailure(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                return
            }
            withProfile { queryByUUID(uuid) }?.takeIf { it.imported }?.let { withProfile { setActive(it) } }
            setResult(Activity.RESULT_OK)
            finish()
        } finally {
            design.setBusy(false)
        }
    }

    private suspend fun save(design: DesyncSettingsDesign, uuid: UUID, form: DesyncSettingsForm) {
        if (form.isEmpty) {
            design.showMessage(R.string.desync_nothing_selected)
            return
        }
        design.setBusy(true)
        try {
            val error = withProfile { updateDesyncSettings(uuid, form.toSettings().encode()) }
            if (error != null) {
                showFailure(error)
            } else {
                design.showMessage(null)
                design.showToast(R.string.desync_saved, ToastDuration.Short)
            }
        } finally {
            design.setBusy(false)
        }
    }

    private suspend fun saveOverlay(design: DesyncSettingsDesign, uuid: UUID, form: DesyncSettingsForm) {
        if (design.overlayEnabled && form.isEmpty) {
            design.showMessage(R.string.desync_nothing_selected)
            return
        }
        val overlay = DesyncOverlay(enabled = design.overlayEnabled, settings = form.toSettings())
        design.setBusy(true)
        try {
            val error = withProfile { writeDesyncOverlay(uuid, DesyncOverlayState.encodeOverlay(overlay)) }
            if (error != null) {
                showFailure(error)
            } else {
                design.showMessage(null)
                design.showToast(R.string.desync_saved, ToastDuration.Short)
            }
        } finally {
            design.setBusy(false)
        }
    }

    private suspend fun showFailure(reason: String) {
        design?.showMessage(reason)
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@DesyncSettingsActivity)
                .setTitle(R.string.desync_failed_title)
                .setMessage(reason)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    companion object {
        /** The setup screen of a new Desync-only profile. */
        fun setupIntent(): Intent = DesyncSettingsActivity::class.intent

        /** The settings of the Desync-only profile [uuid], or desync on top of the subscription [uuid]. */
        fun settingsIntent(uuid: UUID): Intent = DesyncSettingsActivity::class.intent.setUUID(uuid)
    }
}
