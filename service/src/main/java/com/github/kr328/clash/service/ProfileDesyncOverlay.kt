package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.DesyncOverlayState
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ConfigScriptPolicy
import com.github.kr328.clash.service.util.DesyncOverlayPolicy
import com.github.kr328.clash.service.util.ProfileComposer
import com.github.kr328.clash.service.util.ProfileOverlay
import com.github.kr328.clash.service.util.UserLayerStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Owns desync on top of the subscription for [ProfileManager]: its state for the settings screen,
 * and storing it in the user layer with a re-compose, like [ProfileConfigScripts] does for scripts.
 */
internal class ProfileDesyncOverlay(
    private val context: Context,
    private val store: ServiceStore,
    private val userLayerStore: UserLayerStore,
) {
    suspend fun read(uuid: UUID): String = withContext(Dispatchers.IO) {
        val overlay = userLayerStore.load(uuid).desync
        val operator = DesyncOverlayPolicy.operatorFor(context, uuid)
        DesyncOverlayState(
            overlay = overlay,
            operator = operator.name.lowercase(),
            active = DesyncOverlayPolicy.effective(overlay, operator) != null,
        ).encode()
    }

    suspend fun write(uuid: UUID, overlayJson: String): String? = withContext(Dispatchers.IO) {
        val overlay = DesyncOverlayState.decodeOverlay(overlayJson) ?: return@withContext "malformed desync settings"
        val imported = ImportedDao().queryByUUID(uuid) ?: return@withContext "profile not found"
        if (imported.desyncSettings != null) return@withContext "a desync-only profile has no subscription"
        val operator = DesyncOverlayPolicy.operatorFor(context, uuid)
        if (operator == DesyncOverlayPolicy.Operator.Deny) {
            return@withContext "desync is disabled by the subscription operator"
        }
        val profileDir = File(context.importedDir, uuid.toString())
        // Refuse to store an overlay that cannot apply, so the user hears why now rather than
        // finding it silently missing after every compose.
        DesyncOverlayPolicy.effective(overlay, operator)?.let { settings ->
            val base = ProfileComposer.subscriptionFile(profileDir).takeIf { it.isFile }?.readText()
                ?: return@withContext "the profile has no config yet"
            val result = Clash.composeDesyncOverlay(base, settings.toRequest())
            if (result.yaml.isNullOrBlank()) return@withContext result.error ?: "desync could not be added"
        }
        userLayerStore.update(uuid) { it.copy(desync = overlay) }
        runCatching {
            ProfileOverlay.refreshFromStore(
                profileDir = profileDir,
                uuid = uuid,
                importedDir = context.importedDir,
                store = store,
                scriptRunner = ConfigScriptPolicy.runnerFor(context, uuid, imported.name),
                desyncRunner = DesyncOverlayPolicy.runnerFor(context, uuid),
            )
        }.onFailure { Log.w("Desync overlay: refresh failed for $uuid", it) }
        context.sendProfileChanged(uuid)
        null
    }
}
