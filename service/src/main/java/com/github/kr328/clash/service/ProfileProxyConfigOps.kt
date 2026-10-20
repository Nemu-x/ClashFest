package com.github.kr328.clash.service

import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.util.ProxyDialerYamlEdit
import com.github.kr328.clash.service.util.ProxyGroupsYamlEdit
import com.github.kr328.clash.service.util.ProxyProvidersYamlEdit
import com.github.kr328.clash.service.util.RuleProvidersYamlEdit
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Edits of a profile's proxy-side YAML: rule- and proxy-providers, relay proxy groups, dialer
 * (proxy-chain) entries and the provider label file. Each write mirrors its intent into the
 * user layer so it survives subscription updates.
 */
internal class ProfileProxyConfigOps(
    private val env: ProfileEditEnv,
    private val previews: ProfilePreviewStore,
) {
    private val context = env.context
    private val ruleApplyService = env.ruleApplyService
    private val userLayerStore = env.userLayerStore

    private fun syncLayerFromConfig(uuid: UUID) = env.syncLayerFromConfig(uuid)

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
                    syncLayerFromConfig(uuid)
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
            context.getString(R.string.yaml_edit_rule_providers),
            layerMutation = { it.copy(ruleProviders = yaml) },
        ) { current ->
            RuleProvidersYamlEdit.mergeIntoConfig(current, yaml)
        }
    }

    suspend fun readProxyProvidersYaml(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext null
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext null
            }
            try {
                ProxyProvidersYamlEdit.extractBlock(file.readText())
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun replaceProxyProvidersYaml(uuid: UUID, yaml: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext false
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext false
            }
            try {
                val merged = ProxyProvidersYamlEdit.mergeIntoConfig(file.readText(), yaml)
                file.writeText(merged)
                // Overlay: capture the user's proxy-providers delta into the layer (survives updates).
                runCatching {
                    syncLayerFromConfig(uuid)
                    userLayerStore.update(uuid) { it.copy(proxyProviders = yaml) }
                }
                context.sendProfileChanged(uuid)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun previewReplaceProxyProvidersYaml(uuid: UUID, yaml: String): String? {
        return previews.previewConfigMutation(
            uuid,
            context.getString(R.string.yaml_edit_proxy_providers),
            layerMutation = { it.copy(proxyProviders = yaml) },
        ) { current ->
            ProxyProvidersYamlEdit.mergeIntoConfig(current, yaml)
        }
    }

    suspend fun appendRelayProxyGroup(
        uuid: UUID,
        groupName: String,
        providerKeys: List<String>,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext false
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext false
            }
            try {
                val text = file.readText()
                val merged = ProxyGroupsYamlEdit.appendSelectGroupUsingProviders(text, groupName, providerKeys)
                    ?: return@withContext false
                file.writeText(merged)
                // Overlay: capture the relay group so it survives subscription updates.
                runCatching {
                    syncLayerFromConfig(uuid)
                    userLayerStore.update(uuid) { l ->
                        l.copy(
                            relayGroups = l.relayGroups.filterNot { it.name == groupName } +
                                com.github.kr328.clash.service.util.RelayGroup(groupName, providerKeys),
                        )
                    }
                }
                context.sendProfileChanged(uuid)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun previewAppendRelayProxyGroup(
        uuid: UUID,
        groupName: String,
        providerKeys: List<String>,
    ): String? {
        return previews.previewConfigMutation(
            uuid,
            context.getString(R.string.yaml_edit_proxy_group),
            layerMutation = { l ->
                l.copy(
                    relayGroups = l.relayGroups.filterNot { it.name == groupName } +
                        com.github.kr328.clash.service.util.RelayGroup(groupName, providerKeys),
                )
            },
        ) { current ->
            ProxyGroupsYamlEdit.appendSelectGroupUsingProviders(current, groupName, providerKeys)
                ?: throw IllegalArgumentException(context.getString(R.string.yaml_edit_group_exists))
        }
    }

    suspend fun removeProxyGroup(uuid: UUID, groupName: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext false
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext false
            }
            try {
                val merged = ProxyGroupsYamlEdit.removeGroupByName(file.readText(), groupName)
                    ?: return@withContext false
                file.writeText(merged)
                runCatching {
                    syncLayerFromConfig(uuid)
                    userLayerStore.update(uuid) { l -> l.copy(relayGroups = l.relayGroups.filterNot { it.name == groupName }) }
                }
                context.sendProfileChanged(uuid)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun previewRemoveProxyGroup(uuid: UUID, groupName: String): String? {
        return previews.previewConfigMutation(
            uuid,
            context.getString(R.string.yaml_edit_proxy_group),
            layerMutation = { l -> l.copy(relayGroups = l.relayGroups.filterNot { it.name == groupName }) },
        ) { current ->
            ProxyGroupsYamlEdit.removeGroupByName(current, groupName)
                ?: throw IllegalArgumentException(context.getString(R.string.yaml_edit_group_not_found))
        }
    }

    suspend fun setProxyDialerProxy(
        uuid: UUID,
        targetProxyName: String,
        dialerProxyName: String?,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext false
            }
            val dir = File(context.importedDir, uuid.toString())
            try {
                val ok = ProxyDialerYamlEdit.applyDialerProxy(dir, targetProxyName, dialerProxyName)
                if (ok) {
                    runCatching { syncLayerFromConfig(uuid) }
                    // E-01/E-05: record the proxy-chain intent directly in the user layer.
                    runCatching {
                        val dp = dialerProxyName?.takeIf { it.isNotBlank() }
                        userLayerStore.update(uuid) { l ->
                            l.copy(
                                proxyChain = if (dp != null) l.proxyChain + (targetProxyName to dp)
                                else l.proxyChain - targetProxyName,
                            )
                        }
                    }
                    context.sendProfileChanged(uuid)
                }
                ok
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun previewSetProxyDialerProxy(
        uuid: UUID,
        targetProxyName: String,
        dialerProxyName: String?,
    ): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            val dir = File(context.importedDir, uuid.toString())
            try {
                val patch = ProxyDialerYamlEdit.previewDialerProxy(dir, targetProxyName, dialerProxyName)
                    ?: throw IllegalArgumentException(context.getString(R.string.yaml_edit_proxy_not_found))
                previews.createPreview(
                    uuid = uuid,
                    title = context.getString(R.string.yaml_edit_proxy_chain),
                    files = listOf(
                        previews.filePreview(
                            dir,
                            patch.relativePath,
                            patch.currentYaml,
                            patch.proposedYaml,
                            patch.providerFile,
                        ),
                    ),
                    // E-01/E-05: the proxy-chain UI applies via preview->commit, so the chain intent
                    // must be recorded on the layer here (same {target: dialer} map compose expects),
                    // otherwise it's lost on recompose now that config.yaml extraction is gone.
                    layerMutation = { l ->
                        val dp = dialerProxyName?.takeIf { it.isNotBlank() }
                        l.copy(
                            proxyChain = if (dp != null) l.proxyChain + (targetProxyName to dp)
                            else l.proxyChain - targetProxyName,
                        )
                    },
                )
            } catch (e: Exception) {
                previews.createInvalidPreview(context.getString(R.string.yaml_edit_proxy_chain), "", "", e)
            }
        }
    }

    suspend fun listProxyDialerChains(uuid: UUID): List<String> {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext emptyList()
            }
            val dir = File(context.importedDir, uuid.toString())
            try {
                ProxyDialerYamlEdit.listDialerChains(dir).map { row ->
                    listOf(row.targetName, row.dialerName, row.relativePath).joinToString("\u001F")
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun clearAllProxyDialerChains(uuid: UUID): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext false
            }
            val dir = File(context.importedDir, uuid.toString())
            try {
                val ok = ProxyDialerYamlEdit.clearAllDialerProxies(dir)
                if (ok) {
                    // E-01/E-05: clear the proxy-chain intent in the user layer too.
                    runCatching { userLayerStore.update(uuid) { it.copy(proxyChain = emptyMap()) } }
                    context.sendProfileChanged(uuid)
                }
                ok
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun previewClearAllProxyDialerChains(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            val dir = File(context.importedDir, uuid.toString())
            try {
                val patches = ProxyDialerYamlEdit.previewClearAllDialerProxies(dir)
                if (patches.isEmpty()) throw IllegalArgumentException(context.getString(R.string.yaml_edit_no_chains))
                previews.createPreview(
                    uuid = uuid,
                    title = context.getString(R.string.yaml_edit_proxy_chains),
                    files = patches.map {
                        previews.filePreview(dir, it.relativePath, it.currentYaml, it.proposedYaml, it.providerFile)
                    },
                    // E-01/E-05: clearing all chains must clear the layer intent too, else compose
                    // re-applies them from the store on recompose.
                    layerMutation = { it.copy(proxyChain = emptyMap()) },
                )
            } catch (e: Exception) {
                previews.createInvalidPreview(context.getString(R.string.yaml_edit_proxy_chains), "", "", e)
            }
        }
    }

    suspend fun readProxyProviderLabelsJson(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            val f = File(context.importedDir, "$uuid/proxy_providers_labels.json")
            if (!f.isFile) return@withContext null
            try {
                f.readText()
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun writeProxyProviderLabelsJson(uuid: UUID, json: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext false
            try {
                File(context.importedDir, "$uuid/proxy_providers_labels.json").writeText(json)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
