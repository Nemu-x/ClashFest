package com.github.kr328.clash.service

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigScriptError
import com.github.kr328.clash.core.model.ConfigScriptResult
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.ConfigScriptFailure
import com.github.kr328.clash.service.model.ConfigScriptState
import com.github.kr328.clash.service.util.ChainEndpoint
import com.github.kr328.clash.service.util.ConfigScriptPolicy
import com.github.kr328.clash.service.util.DnsHostsConfig
import com.github.kr328.clash.service.util.DnsHostsYamlEdit
import com.github.kr328.clash.service.util.PreviewResourceLimits
import com.github.kr328.clash.service.util.ProfileComposer
import com.github.kr328.clash.service.util.ProfileOverlay
import com.github.kr328.clash.service.util.RuleApplyService
import com.github.kr328.clash.service.util.SubscriptionChain
import com.github.kr328.clash.service.util.SubscriptionChainComposer
import com.github.kr328.clash.service.util.TunnelsConfig
import com.github.kr328.clash.service.util.TunnelsYamlEdit
import com.github.kr328.clash.service.util.UserScript
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Edits that live in the user layer rather than in the subscription body: rule state, the
 * config script, DNS & Hosts, tunnels and the subscription chain.
 */
internal class ProfileOverlayOps(
    private val env: ProfileEditEnv,
    private val previews: ProfilePreviewStore,
    private val snapshots: ProfileSnapshotReader,
) {
    private val context = env.context
    private val store = env.store
    private val ruleApplyService = env.ruleApplyService
    private val userLayerStore = env.userLayerStore
    private val previewJson = env.previewJson

    private fun syncLayerFromConfig(uuid: UUID) = env.syncLayerFromConfig(uuid)

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
    ): String? = previews.previewRuleDryRun(uuid, context.getString(R.string.yaml_edit_rules), ruleApplyService.dryRunMergeProviderShortcut(uuid, ruleProvidersYaml, prependRuleLine))

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
                )
            }.onFailure { Log.w("ConfigScript: overlay refresh failed for $uuid", it) }
            context.sendProfileChanged(uuid)
            null
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
                syncLayerFromConfig(uuid)
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
            context.getString(R.string.yaml_edit_dns_hosts),
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
            context.getString(R.string.yaml_edit_tunnels),
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

    suspend fun readSubscriptionChainNodes(uuid: UUID): List<String> = withContext(Dispatchers.IO) {
        if (ImportedDao().queryByUUID(uuid) == null) return@withContext emptyList()
        val dir = File(context.importedDir, uuid.toString())
        val file = File(dir, "config.yaml")
        if (!file.isFile || file.length() > PreviewResourceLimits.MAX_CONFIG_BYTES) return@withContext emptyList()
        SubscriptionChainComposer.proxies(snapshots.getOrParseSnapshot(uuid, file), dir).keys.sorted()
    }

    suspend fun readSubscriptionChain(uuid: UUID): String? = withContext(Dispatchers.IO) {
        if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
        userLayerStore.load(uuid).subscriptionChain?.let { previewJson.encodeToString(SubscriptionChain.serializer(), it) }
    }

    suspend fun previewSubscriptionChain(
        uuid: UUID, firstProfile: UUID, firstProxy: String, exitProfile: UUID, exitProxy: String,
    ): String? = withContext(Dispatchers.IO) {
        require(uuid == exitProfile)
        require(listOf(uuid, firstProfile).all { ImportedDao().queryByUUID(it) != null })
        syncLayerFromConfig(uuid)
        val old = userLayerStore.load(uuid).subscriptionChain
        val chosen = SubscriptionChain(old?.id ?: UUID.randomUUID().toString(),
            ChainEndpoint(firstProfile.toString(), firstProxy, ""), ChainEndpoint(exitProfile.toString(), exitProxy, ""))
        val chain = SubscriptionChainComposer.refresh(chosen, context.importedDir) { dir ->
            snapshots.getOrParseSnapshot(UUID.fromString(dir.name), File(dir, "config.yaml"))
        }
        previews.previewConfigMutation(uuid, context.getString(R.string.yaml_edit_proxy_chain),
            layerMutation = { it.copy(subscriptionChain = chain) }) { current ->
            SubscriptionChainComposer.compose(current, chain, old).also { proposed ->
                require(Clash.validateProfileBytes(proposed) == null) { "The engine rejected this proxy chain" }
            }
        }
    }

    suspend fun previewRemoveSubscriptionChain(uuid: UUID): String? = withContext(Dispatchers.IO) {
        val layer = userLayerStore.load(uuid)
        if (layer.subscriptionChain == null) return@withContext null
        val profileName = ImportedDao().queryByUUID(uuid)?.name.orEmpty()
        previews.previewConfigMutation(uuid, context.getString(R.string.yaml_edit_proxy_chain),
            layerMutation = { it.copy(subscriptionChain = null) }) {
            val base = com.github.kr328.clash.service.util.ProfileComposer.subscriptionFile(File(context.importedDir, uuid.toString())).readText()
            val urls = com.github.kr328.clash.service.util.GeoDataSources.resolve(store.geoDataSourcePreset,
                store.geoDataCustomGeoIp, store.geoDataCustomGeoSite, store.geoDataCustomMmdb, store.geoDataCustomAsn)
            com.github.kr328.clash.service.util.ConfigComposer.compose(base, layer.copy(subscriptionChain = null), urls,
                store.proxyHardeningMode, scriptRunner = ConfigScriptPolicy.runnerFor(context, uuid, profileName),
                realityCompat = store.realityComposeRewrite).also { require(Clash.validateProfileBytes(it) == null) }
        }
    }
}
