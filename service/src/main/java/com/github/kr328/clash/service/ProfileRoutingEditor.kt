package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.DnsHostsConfig
import com.github.kr328.clash.service.util.DnsHostsYamlEdit
import com.github.kr328.clash.service.util.TunnelsConfig
import com.github.kr328.clash.service.util.TunnelsYamlEdit
import com.github.kr328.clash.service.util.UserLayerStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.RuleApplyService
import com.github.kr328.clash.service.util.RuleProvidersYamlEdit
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

/**
 * Owns [ProfileManager]'s routing edits for a profile: rule providers and the provider shortcut,
 * the structured rule state, and the DNS & Hosts / tunnels editors (read, preview, managed flags).
 */
internal class ProfileRoutingEditor(
    private val context: Context,
    private val store: ServiceStore,
    private val previewJson: Json,
    private val ruleApplyService: RuleApplyService,
    private val userLayerStore: UserLayerStore,
    private val layerSync: ProfileLayerSync,
    private val previews: ProfileYamlPreviews,
) {
    suspend fun mergeRuleProviderYaml(
        uuid: UUID,
        ruleProvidersYaml: String,
        prependRuleLine: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext false
            val ok = ruleApplyService.mergeProviderShortcut(uuid, ruleProvidersYaml, prependRuleLine)
            Log.d("mergeRuleProviderYaml ok=$ok")
            ok
        }
    }

    suspend fun previewMergeRuleProviderYaml(
        uuid: UUID,
        ruleProvidersYaml: String,
        prependRuleLine: String,
    ): String? = previews.previewRuleDryRun(uuid, "Rules", ruleApplyService.dryRunMergeProviderShortcut(uuid, ruleProvidersYaml, prependRuleLine))

    suspend fun readRuleProvidersYaml(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext null
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext null
            }
            try {
                RuleProvidersYamlEdit.extractBlock(file.readText())
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun replaceRuleProvidersYaml(uuid: UUID, yaml: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext false
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext false
            }
            try {
                val merged = RuleProvidersYamlEdit.mergeIntoConfig(file.readText(), yaml)
                file.writeText(merged)
                // Keep structured repository synchronized with manual YAML edits.
                ruleApplyService.readStateJson(uuid)
                // Overlay: capture the user's rule-providers delta into the layer (survives updates).
                runCatching {
                    layerSync.syncLayerFromConfig(uuid)
                    userLayerStore.update(uuid) { it.copy(ruleProviders = yaml) }
                }
                context.sendProfileChanged(uuid)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun previewReplaceRuleProvidersYaml(uuid: UUID, yaml: String): String? {
        return previews.previewConfigMutation(
            uuid,
            "Rule providers",
            layerMutation = { it.copy(ruleProviders = yaml) },
        ) { current ->
            RuleProvidersYamlEdit.mergeIntoConfig(current, yaml)
        }
    }

    suspend fun readRuleState(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            ruleApplyService.readStateJson(uuid)
        }
    }

    suspend fun applyRuleState(uuid: UUID, stateJson: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext false
            val ok = ruleApplyService.applyStateJson(uuid, stateJson)
            if (ok) runCatching {
                layerSync.syncLayerFromConfig(uuid)
                // E-01/E-05: mirror the saved (normalized) rule state into the user layer directly,
                // so the layer holds explicit rule intent instead of it being re-derived on sync.
                ruleApplyService.readStateJson(uuid)?.let { saved ->
                    userLayerStore.update(uuid) { it.copy(rules = ruleApplyService.parseState(saved)) }
                }
            }
            Log.d("applyRuleState ok=$ok")
            ok
        }
    }

    suspend fun queryDnsHostsConfigJson(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            val dir = File(context.importedDir, uuid.toString())
            if (!File(dir, "config.yaml").isFile) return@withContext null
            val snapshot = runCatching { Clash.parseProfileSnapshot(dir) }.getOrNull()
                ?: return@withContext null
            // An exception here crosses the binder and kills the settings activity.
            val config = runCatching { DnsHostsConfig.fromSnapshot(snapshot) }
                .onFailure { Log.w("DNS & Hosts: snapshot not representable, editor starts empty", it) }
                .getOrNull() ?: return@withContext null
            previewJson.encodeToString(DnsHostsConfig.serializer(), config)
        }
    }

    suspend fun previewSetDnsHosts(uuid: UUID, configJson: String): String? {
        val config = previewJson.decodeFromString(DnsHostsConfig.serializer(), configJson)
        // E-01/E-05: write the DNS/hosts intent to the user layer directly (explicit intent), the
        // same way rule-/proxy-providers already do, instead of relying on config.yaml extraction.
        return previews.previewConfigMutation(
            uuid,
            "DNS & Hosts",
            layerMutation = { it.copy(dnsHosts = config) },
        ) { current ->
            DnsHostsYamlEdit.render(current, config)
        }
    }

    suspend fun isDnsHostsManaged(uuid: UUID): Boolean {
        return withContext(Dispatchers.IO) { store.isDnsHostsManaged(uuid) }
    }

    suspend fun setDnsHostsManaged(uuid: UUID, managed: Boolean) {
        withContext(Dispatchers.IO) { store.setDnsHostsManaged(uuid, managed) }
    }

    suspend fun queryTunnelsConfigJson(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            val dir = File(context.importedDir, uuid.toString())
            if (!File(dir, "config.yaml").isFile) return@withContext null
            val snapshot = runCatching { Clash.parseProfileSnapshot(dir) }.getOrNull()
                ?: return@withContext null
            val config = TunnelsConfig.fromSnapshot(snapshot)
            previewJson.encodeToString(TunnelsConfig.serializer(), config)
        }
    }

    suspend fun previewSetTunnels(uuid: UUID, configJson: String): String? {
        val config = previewJson.decodeFromString(TunnelsConfig.serializer(), configJson)
        // E-01/E-05: write the tunnels intent to the user layer directly (explicit intent).
        return previews.previewConfigMutation(
            uuid,
            "Tunnels",
            layerMutation = { it.copy(tunnels = config) },
        ) { current ->
            TunnelsYamlEdit.render(current, config)
        }
    }

    suspend fun isTunnelsManaged(uuid: UUID): Boolean {
        return withContext(Dispatchers.IO) { store.isTunnelsManaged(uuid) }
    }

    suspend fun setTunnelsManaged(uuid: UUID, managed: Boolean) {
        withContext(Dispatchers.IO) { store.setTunnelsManaged(uuid, managed) }
    }

    suspend fun previewRuleStateYaml(uuid: UUID, stateJson: String): String? =
        withContext(Dispatchers.IO) {
            RuleApplyService(context).dryRunStateJson(uuid, stateJson)?.proposedYaml
        }

    suspend fun readRuleEditorBundle(uuid: UUID): String? =
        withContext(Dispatchers.IO) { RuleApplyService(context).readEditorBundle(uuid) }
}
