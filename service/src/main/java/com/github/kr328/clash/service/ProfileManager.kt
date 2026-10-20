package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.branding.BrandRefresh
import com.github.kr328.clash.service.branding.BrandStore
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.model.ProxyTransportInfo
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.model.ConfigScriptFailure
import com.github.kr328.clash.service.model.ConfigScriptState
import com.github.kr328.clash.service.util.PreviewResourceLimits
import com.github.kr328.clash.service.util.UserLayerStore
import com.github.kr328.clash.service.util.RawConfigEdit
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import com.github.kr328.clash.service.util.ProxyGroupsYamlPreview
import com.github.kr328.clash.service.util.ProxyTransportYamlPreview
import com.github.kr328.clash.service.util.ProxyYamlPreview
import com.github.kr328.clash.service.util.RuleApplyService
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private val ruleApplyService = RuleApplyService(context)
    private val previewJson = Json { ignoreUnknownKeys = true }

    /** User edit layer (config-overlay-architecture): edits live here as intent, not in config.yaml. */
    private val userLayerStore = UserLayerStore(context.importedDir)

    private val layerSync = ProfileLayerSync(context, store, ruleApplyService, userLayerStore)
    private val snapshots = ProfileSnapshotCache()
    private val previews = ProfileYamlPreviews(context, previewJson, ruleApplyService, userLayerStore, layerSync)
    private val routingEditor = ProfileRoutingEditor(
        context, store, previewJson, ruleApplyService, userLayerStore, layerSync, previews,
    )
    private val proxyEditor = ProfileProxyEditor(context, userLayerStore, layerSync, previews)
    private val configScripts = ProfileConfigScripts(context, store, userLayerStore)
    private val records = ProfileRecords(context, store)
    private val subscriptionHeaders = SubscriptionHeaderSync(context)

    /**
     * UUIDs that currently have a manual [update] in flight. The second click
     * on the "refresh" button arrives while the first is still running and
     * would otherwise start a parallel fetch — that's what produced the
     * "io read/write on closed pipe" errors in the wild. Set membership is
     * the only signal; entries are removed in a `finally` so a crash or
     * coroutine cancellation can't strand a profile in "updating forever".
     */
    private val updatingUuids = java.util.Collections.synchronizedSet(mutableSetOf<UUID>())

    init {
        launch {
            Database.database //.init

            ProfileReceiver.rescheduleAll(context)
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String): UUID =
        records.create(type, name, source)

    override suspend fun clone(uuid: UUID): UUID =
        records.clone(uuid)

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) {
        records.patch(uuid, name, source, interval, ageSecretKey)
    }

    override suspend fun renameImported(uuid: UUID, name: String) {
        records.renameImported(uuid, name)
    }

    override suspend fun applySubscriptionUpdateInterval(uuid: UUID, intervalMillis: Long) {
        withContext(Dispatchers.IO) {
            val min = java.util.concurrent.TimeUnit.MINUTES.toMillis(15)
            val interval = intervalMillis.coerceAtLeast(min)
            val imported = ImportedDao().queryByUUID(uuid) ?: return@withContext
            if (imported.type != Profile.Type.Url) return@withContext
            if (imported.interval == interval) return@withContext

            val updated = imported.copy(interval = interval)
            ImportedDao().update(updated)

            PendingDao().queryByUUID(uuid)?.let { pending ->
                PendingDao().update(pending.copy(interval = interval))
            }

            ProfileReceiver.cancelNext(context, imported)
            ProfileReceiver.scheduleNext(context, updated)
            context.sendProfileChanged(uuid)
        }
    }

    override suspend fun update(uuid: UUID, callback: IFetchObserver?) {
        // Per-uuid dedupe: drop the call if an update for this profile is
        // already in flight. The UI also blocks rapid taps via a modal
        // progress dialog, but this is the belt-and-suspenders guarantee:
        // any caller (auto-update WorkManager, notification action, AIDL
        // client) gets the same protection.
        if (!updatingUuids.add(uuid)) {
            Log.d("update($uuid) skipped — already in flight")
            return
        }
        try {
            val imported = ImportedDao().queryByUUID(uuid) ?: return
            if (imported.type != Profile.Type.Url) return

            // Direct invocation so the suspend point holds the lock for the
            // FULL fetch+verify duration. The previous implementation called
            // scheduleUpdate(true) which dispatched the work to ProfileWorker
            // (foreground service) and returned immediately — the lock would
            // have released after ~50ms while the real work ran in the
            // background, defeating the dedupe and letting a second tap kick
            // off a parallel fetch that fought the first one for the YAML
            // pipe. ProfileProcessor.update already holds its own
            // processLock so reentrancy from auto-update is safe.
            //
            // Side effect of bypassing ProfileWorker: the worker is what used
            // to broadcast Profile-Update-Completed / Failed (so observers
            // could surface a toast). We now emit those broadcasts here so
            // the UI keeps getting the same lifecycle events on every path.
            try {
                ProfileProcessor.update(context, uuid, callback)

                if (imported.source.startsWith("https://", true)) {
                    updateFlow(imported)
                }

                // Re-arm the timer-based auto-update from the new mtime so the
                // next automatic refresh fires at the correct interval — we no
                // longer go through scheduleUpdate() which used to do this for us.
                ImportedDao().queryByUUID(uuid)?.let {
                    ProfileReceiver.scheduleNext(context, it)
                }
                context.sendProfileUpdateCompleted(uuid)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    context.sendProfileUpdateFailed(uuid, reason)
                }
                throw e
            }
        } finally {
            updatingUuids.remove(uuid)
        }
    }

    suspend fun updateFlow(old: Imported) {
        subscriptionHeaders.updateFlow(old)
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)

        records.scheduleUpdate(uuid, false)
    }

    override suspend fun release(uuid: UUID) {
        ProfileProcessor.release(context, uuid)
    }

    override suspend fun delete(uuid: UUID) {
        ImportedDao().queryByUUID(uuid)?.also {
            ProfileReceiver.cancelNext(context, it)
        }

        ProfileProcessor.delete(context, uuid)
        BrandRefresh.onProfileDeleted(context, uuid)
        store.clearSubscriptionPoliciesFor(uuid)
        snapshots.invalidateSnapshotCache(uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? {
        return records.resolveProfile(uuid)
    }

    override suspend fun hasPendingConfigEdits(uuid: UUID): Boolean = withContext(Dispatchers.IO) {
        RawConfigEdit.isEdited(
            imported = File(context.importedDir, "$uuid/config.yaml"),
            candidate = File(context.pendingDir, "$uuid/config.yaml"),
        )
    }

    override suspend fun queryAll(): List<Profile> {
        return withContext(Dispatchers.IO) {
            // Bulk-load both tables once instead of two per-uuid queries (N+1) inside the loop.
            val ordered = ImportedDao().queryAllOrderedUUIDs().map { it.uuid }.distinct()
            val importedByUuid = ImportedDao().queryAll().associateBy { it.uuid }
            val pendingByUuid = PendingDao().queryAll().associateBy { it.uuid }
            ordered.mapNotNull { uuid -> records.buildProfile(uuid, importedByUuid[uuid], pendingByUuid[uuid]) }
        }
    }

    override suspend fun reorder(uuids: List<String>) {
        withContext(Dispatchers.IO) {
            uuids.mapNotNull { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }.distinct().forEachIndexed { index, uuid ->
                val order = index.toLong()
                ImportedDao().updateProfileOrder(uuid, order)
                PendingDao().updateProfileOrder(uuid, order)
            }
        }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active)) {
            records.resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    override suspend fun mergeRuleProviderYaml(
        uuid: UUID,
        ruleProvidersYaml: String,
        prependRuleLine: String,
    ): Boolean = routingEditor.mergeRuleProviderYaml(uuid, ruleProvidersYaml, prependRuleLine)

    override suspend fun previewMergeRuleProviderYaml(
        uuid: UUID,
        ruleProvidersYaml: String,
        prependRuleLine: String,
    ): String? = routingEditor.previewMergeRuleProviderYaml(uuid, ruleProvidersYaml, prependRuleLine)

    override suspend fun readProxyGroupsPreview(uuid: UUID): Map<String, ProxyGroupPreviewRow> {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext emptyMap()
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile || file.length() > PreviewResourceLimits.MAX_CONFIG_BYTES) {
                return@withContext emptyMap()
            }
            try {
                val snapshot = snapshots.getOrParseSnapshot(uuid, file)
                // Ask the engine first. It resolves include-all* / use: / filter / exclude-filter /
                // exclude-type exactly as the running tunnel does, so the disconnected UI reports the
                // same membership as the connected one. The Kotlin preview below re-implements those
                // semantics and has repeatedly diverged (a filter with a quantifier or lookahead
                // silently collapsed a group to its declared proxies:; use:-backed groups resolved to
                // nothing), so it stays only as a fallback for YAML the engine refuses to parse.
                //
                // includeHidden = true in both paths: when the live engine path serves the UI, the
                // picker pills walk every group (visible + hidden) via
                // Clash.queryAllProxyGroupNamesIncludingHidden, so the offline preview must match that
                // universe — otherwise hidden auto subgroups have no rows during the warmup race and
                // the expanded carriage flashes empty until proxyDetails arrives.
                snapshots.engineProxyGroupsPreview(uuid, file, snapshot)
                    ?: ProxyGroupsYamlPreview.parseProxyGroupsPreview(
                        snapshot,
                        file.parentFile,
                        includeHidden = true,
                    )
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    override suspend fun readProxyTransports(uuid: UUID): Map<String, ProxyTransportInfo> {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext emptyMap()
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile || file.length() > PreviewResourceLimits.MAX_CONFIG_BYTES) {
                return@withContext emptyMap()
            }
            try {
                val snapshot = snapshots.getOrParseSnapshot(uuid, file)
                ProxyTransportYamlPreview.parse(snapshot, file.parentFile)
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    override suspend fun readRuleProvidersYaml(uuid: UUID): String? =
        routingEditor.readRuleProvidersYaml(uuid)

    override suspend fun readConfigYaml(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext null
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext null
            }
            try {
                file.readText()
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun readConfigScript(uuid: UUID): ConfigScriptState =
        configScripts.readConfigScript(uuid)

    override suspend fun checkConfigScript(uuid: UUID, source: String): ConfigScriptFailure? =
        configScripts.checkConfigScript(uuid, source)

    override suspend fun writeConfigScript(
        uuid: UUID,
        source: String,
        enabled: Boolean,
    ): ConfigScriptFailure? = configScripts.writeConfigScript(uuid, source, enabled)

    override suspend fun replaceRuleProvidersYaml(uuid: UUID, yaml: String): Boolean =
        routingEditor.replaceRuleProvidersYaml(uuid, yaml)

    override suspend fun previewReplaceRuleProvidersYaml(uuid: UUID, yaml: String): String? =
        routingEditor.previewReplaceRuleProvidersYaml(uuid, yaml)

    override suspend fun readProxyProvidersYaml(uuid: UUID): String? =
        proxyEditor.readProxyProvidersYaml(uuid)

    override suspend fun replaceProxyProvidersYaml(uuid: UUID, yaml: String): Boolean =
        proxyEditor.replaceProxyProvidersYaml(uuid, yaml)

    override suspend fun previewReplaceProxyProvidersYaml(uuid: UUID, yaml: String): String? =
        proxyEditor.previewReplaceProxyProvidersYaml(uuid, yaml)

    override suspend fun appendRelayProxyGroup(
        uuid: UUID,
        groupName: String,
        providerKeys: List<String>,
    ): Boolean = proxyEditor.appendRelayProxyGroup(uuid, groupName, providerKeys)

    override suspend fun previewAppendRelayProxyGroup(
        uuid: UUID,
        groupName: String,
        providerKeys: List<String>,
    ): String? = proxyEditor.previewAppendRelayProxyGroup(uuid, groupName, providerKeys)

    override suspend fun removeProxyGroup(uuid: UUID, groupName: String): Boolean =
        proxyEditor.removeProxyGroup(uuid, groupName)

    override suspend fun previewRemoveProxyGroup(uuid: UUID, groupName: String): String? =
        proxyEditor.previewRemoveProxyGroup(uuid, groupName)

    override suspend fun setProxyDialerProxy(
        uuid: UUID,
        targetProxyName: String,
        dialerProxyName: String?,
    ): Boolean = proxyEditor.setProxyDialerProxy(uuid, targetProxyName, dialerProxyName)

    override suspend fun previewSetProxyDialerProxy(
        uuid: UUID,
        targetProxyName: String,
        dialerProxyName: String?,
    ): String? = proxyEditor.previewSetProxyDialerProxy(uuid, targetProxyName, dialerProxyName)

    override suspend fun listProxyDialerChains(uuid: UUID): List<String> =
        proxyEditor.listProxyDialerChains(uuid)

    override suspend fun clearAllProxyDialerChains(uuid: UUID): Boolean =
        proxyEditor.clearAllProxyDialerChains(uuid)

    override suspend fun previewClearAllProxyDialerChains(uuid: UUID): String? =
        proxyEditor.previewClearAllProxyDialerChains(uuid)

    override suspend fun readProxyProviderLabelsJson(uuid: UUID): String? =
        proxyEditor.readProxyProviderLabelsJson(uuid)

    override suspend fun writeProxyProviderLabelsJson(uuid: UUID, json: String): Boolean =
        proxyEditor.writeProxyProviderLabelsJson(uuid, json)

    override suspend fun readRuleState(uuid: UUID): String? =
        routingEditor.readRuleState(uuid)

    override suspend fun applyRuleState(uuid: UUID, stateJson: String): Boolean =
        routingEditor.applyRuleState(uuid, stateJson)

    override suspend fun queryDnsHostsConfigJson(uuid: UUID): String? =
        routingEditor.queryDnsHostsConfigJson(uuid)

    override suspend fun previewSetDnsHosts(uuid: UUID, configJson: String): String? =
        routingEditor.previewSetDnsHosts(uuid, configJson)

    override suspend fun isDnsHostsManaged(uuid: UUID): Boolean =
        routingEditor.isDnsHostsManaged(uuid)

    override suspend fun setDnsHostsManaged(uuid: UUID, managed: Boolean) {
        routingEditor.setDnsHostsManaged(uuid, managed)
    }

    override suspend fun queryTunnelsConfigJson(uuid: UUID): String? =
        routingEditor.queryTunnelsConfigJson(uuid)

    override suspend fun previewSetTunnels(uuid: UUID, configJson: String): String? =
        routingEditor.previewSetTunnels(uuid, configJson)

    override suspend fun isTunnelsManaged(uuid: UUID): Boolean =
        routingEditor.isTunnelsManaged(uuid)

    override suspend fun setTunnelsManaged(uuid: UUID, managed: Boolean) {
        routingEditor.setTunnelsManaged(uuid, managed)
    }

    override suspend fun previewRuleStateYaml(uuid: UUID, stateJson: String): String? =
        routingEditor.previewRuleStateYaml(uuid, stateJson)

    override suspend fun readRuleEditorBundle(uuid: UUID): String? =
        routingEditor.readRuleEditorBundle(uuid)

    override suspend fun applyYamlPreview(previewId: String): Boolean =
        previews.applyYamlPreview(previewId)

    override suspend fun readProxyEntryYaml(uuid: UUID, proxyName: String): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext null
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext null
            }
            try {
                val snapshot = snapshots.getOrParseSnapshot(uuid, file)
                ProxyYamlPreview.extractProxyEntry(snapshot, proxyName)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun rememberProxySelection(uuid: UUID, group: String, name: String) {
        withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext
            SelectionDao().setSelected(Selection(uuid, group, name))
        }
    }

    override suspend fun queryProxySelections(uuid: UUID): Map<String, String> {
        return withContext(Dispatchers.IO) {
            SelectionDao().querySelections(uuid).associate { it.proxy to it.selected }
        }
    }

    override suspend fun readImportedConfigYaml(uuid: UUID): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext null
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext null
            }
            try {
                file.readText()
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun readBrandJsonFor(uuid: UUID): String? = withContext(Dispatchers.IO) {
        val store = BrandStore(context)
        val manifest = store.manifestFor(uuid)
        // Visual brand needs isActiveFor (enabled + identity). Operator POLICY flags (hideGlobalMode)
        // apply without branding, so surface the manifest when it carries policy too — otherwise the
        // UI never sees the flag and the restriction silently does nothing.
        if (store.isActiveFor(uuid) || manifest.hasPolicy()) manifest.toJson() else null
    }

    override suspend fun brandLogoPathFor(uuid: UUID, darkTheme: Boolean): String? = withContext(Dispatchers.IO) {
        val store = BrandStore(context)
        if (!store.isActiveFor(uuid)) return@withContext null
        store.logoPathFor(uuid, darkTheme)?.takeIf { File(it).isFile }
    }
}
