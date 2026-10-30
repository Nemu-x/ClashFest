package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigScriptError
import com.github.kr328.clash.core.model.ConfigScriptResult
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.model.ConfigScriptFailure
import com.github.kr328.clash.service.model.ConfigScriptState
import com.github.kr328.clash.service.util.ConfigScriptPolicy
import com.github.kr328.clash.service.util.DesyncOverlayPolicy
import com.github.kr328.clash.service.util.UserScript
import com.github.kr328.clash.service.util.ProfileOverlay
import com.github.kr328.clash.service.util.UserLayerStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * Owns the per-profile JS config script for [ProfileManager]: reading its state, dry-running it
 * against the profile's current config, and storing it in the user layer with a re-compose.
 */
internal class ProfileConfigScripts(
    private val context: Context,
    private val store: ServiceStore,
    private val userLayerStore: UserLayerStore,
) {
    suspend fun readConfigScript(uuid: UUID): ConfigScriptState {
        return withContext(Dispatchers.IO) {
            val layer = userLayerStore.load(uuid)
            ConfigScriptState(
                source = layer.script?.source.orEmpty(),
                enabled = layer.script?.enabled ?: true,
                locked = ConfigScriptPolicy.isLocked(context, uuid),
            )
        }
    }

    suspend fun checkConfigScript(uuid: UUID, source: String): ConfigScriptFailure? {
        return withContext(Dispatchers.IO) {
            if (source.isBlank()) return@withContext null
            // Dry-run against the config the engine is actually holding for this profile, so the
            // check exercises the user's real data — a script can be valid JS and still blow up on
            // a key this particular subscription does not have.
            val current = File(context.importedDir, "$uuid/config.yaml")
                .takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() }
                .orEmpty()
            val name = ImportedDao().queryByUUID(uuid)?.name.orEmpty()
            when (val r = Clash.applyConfigScript(current, source, name)) {
                is ConfigScriptResult.Success -> null
                is ConfigScriptResult.Failure -> ConfigScriptFailure(r.error, r.message)
            }
        }
    }

    suspend fun writeConfigScript(
        uuid: UUID,
        source: String,
        enabled: Boolean,
    ): ConfigScriptFailure? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            if (ConfigScriptPolicy.isLocked(context, uuid)) {
                // Belt and braces: the editor is read-only under an operator lock, but a save must
                // not get through even if that check is ever bypassed.
                return@withContext ConfigScriptFailure(
                    ConfigScriptError.Runtime,
                    "config scripts are disabled by the subscription operator",
                )
            }
            // Refuse to store a script that cannot run — otherwise the profile silently carries a
            // broken script that gets dropped on every compose, and the user is never told again.
            if (enabled && source.isNotBlank()) {
                checkConfigScript(uuid, source)?.let { return@withContext it }
            }

            userLayerStore.update(uuid) { layer ->
                if (source.isBlank()) {
                    layer.copy(script = null)
                } else {
                    layer.copy(script = UserScript(source = source, enabled = enabled))
                }
            }
            // Re-compose so the change is live now rather than at the next update or VPN start.
            runCatching {
                ProfileOverlay.refreshFromStore(
                    profileDir = File(context.importedDir, uuid.toString()),
                    uuid = uuid,
                    importedDir = context.importedDir,
                    store = store,
                    scriptRunner = ConfigScriptPolicy.runnerFor(
                        context, uuid, ImportedDao().queryByUUID(uuid)?.name.orEmpty(),
                    ),
                    desyncRunner = DesyncOverlayPolicy.runnerFor(context, uuid),
                )
            }.onFailure { Log.w("ConfigScript: overlay refresh failed for $uuid", it) }
            context.sendProfileChanged(uuid)
            null
        }
    }
}
