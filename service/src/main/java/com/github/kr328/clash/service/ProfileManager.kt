package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.AgeKeyUrl
import com.github.kr328.clash.common.util.SubscriptionNameGuesser
import com.github.kr328.clash.common.util.SubscriptionUsage
import com.github.kr328.clash.service.branding.BrandRefresh
import com.github.kr328.clash.service.branding.BrandStore
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.ConfigScriptFailure
import com.github.kr328.clash.service.model.ConfigScriptState
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.model.ProxyTransportInfo
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.FetchHeadersFile
import com.github.kr328.clash.service.util.ProfileComposer
import com.github.kr328.clash.service.util.ProfileUpdateTasks
import com.github.kr328.clash.service.util.RawConfigEdit
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private val env = ProfileEditEnv(context, store)
    private val snapshots = ProfileSnapshotReader(context)
    private val previews = ProfilePreviewStore(env)
    private val proxyConfig = ProfileProxyConfigOps(env, previews)
    private val overlay = ProfileOverlayOps(env, previews, snapshots)

    // Serializes nextProfileOrder() + Pending insert pairs. Without it, two concurrent
    // imports (e.g. auto-update + manual click) can both read the same MAX(profileOrder)
    // and produce duplicate ordering values that compare non-deterministically.
    private val profileOrderLock = Mutex()

    private val updateTasks = ProfileUpdateTasks()

    init {
        launch {
            Database.database //.init

            ProfileReceiver.rescheduleAll(context)
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String): UUID {
        val uuid = generateProfileUUID()
        // Operator links may carry the age decryption key in the URL fragment
        // (`…#AGE-SECRET-KEY-…`) — split it into the profile field so the user
        // never types a key by hand. Every import surface (URL form, clipboard,
        // QR, deep-link) funnels through here.
        val split = AgeKeyUrl.split(source)
        profileOrderLock.withLock {
            val profileOrder = nextProfileOrder()
            val pending = Pending(
                uuid = uuid,
                name = name,
                type = type,
                source = split.source,
                interval = 0,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                profileOrder = profileOrder,
                ageSecretKey = split.ageSecretKey,
            )

            PendingDao().insert(pending)
        }

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun clone(uuid: UUID): UUID {
        val newUUID = generateProfileUUID()

        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw FileNotFoundException("profile $uuid not found")

        cloneImportedFiles(uuid, newUUID)

        profileOrderLock.withLock {
            val profileOrder = nextProfileOrder()
            val pending = Pending(
                uuid = newUUID,
                name = imported.name,
                type = Profile.Type.File,
                source = imported.source,
                interval = imported.interval,
                upload = imported.upload,
                total = imported.total,
                download = imported.download,
                expire = imported.expire,
                profileOrder = profileOrder,
                ageSecretKey = imported.ageSecretKey,
            )

            PendingDao().insert(pending)
        }

        return newUUID
    }

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) {
        // A key pasted as part of the URL (fragment) wins over the separate
        // field — it is the more deliberate input, and leaving it inside
        // `source` would leak it into every place that renders the URL.
        val fragmentSplit = AgeKeyUrl.split(source)
        @Suppress("NAME_SHADOWING") val source = fragmentSplit.source
        @Suppress("NAME_SHADOWING") val ageSecretKey = fragmentSplit.ageSecretKey ?: ageSecretKey
        val locked = store.subscriptionShareLinksLockedFor(uuid)
        val resolvedSource =
            if (!locked) {
                source
            } else {
                PendingDao().queryByUUID(uuid)?.source
                    ?: ImportedDao().queryByUUID(uuid)?.source
                    ?: source
            }
        val pending = PendingDao().queryByUUID(uuid)

        if (pending == null) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            cloneImportedFiles(uuid)

            PendingDao().insert(
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = resolvedSource,
                    interval = interval,
                    upload = 0,
                    total = 0,
                    download = 0,
                    expire = 0,
                    profileOrder = imported.profileOrder,
                    ageSecretKey = ageSecretKey?.takeIf { it.isNotBlank() },
                )
            )
        } else {
            val newPending = pending.copy(
                name = name,
                source = resolvedSource,
                interval = interval,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                ageSecretKey = ageSecretKey?.takeIf { it.isNotBlank() },
            )

            PendingDao().update(newPending)
        }
    }

    override suspend fun renameImported(uuid: UUID, name: String) {
        withContext(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return@withContext
            val imported = ImportedDao().queryByUUID(uuid) ?: return@withContext
            if (imported.name == trimmed) return@withContext
            ImportedDao().update(imported.copy(name = trimmed))
            // Mirror to a pending draft if one happens to exist (full edit
            // flow in progress) — otherwise we'd silently overwrite the
            // user's in-progress edit on the next commit. No-op when no draft.
            PendingDao().queryByUUID(uuid)?.let { pending ->
                PendingDao().update(pending.copy(name = trimmed))
            }
            context.sendProfileChanged(uuid)
        }
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
        updateTasks.run(uuid) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("Profile no longer exists")
            require(imported.type == Profile.Type.Url) { "Only URL subscriptions can be updated" }
            try {
                ProfileProcessor.update(context, uuid, callback)
                if (imported.source.startsWith("https://", true)) updateFlow(imported)
                ImportedDao().queryByUUID(uuid)?.let { ProfileReceiver.scheduleNext(context, it) }
                context.sendProfileUpdateCompleted(uuid)
            } catch (error: Exception) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    context.sendProfileUpdateFailed(uuid, reason)
                }
                throw error
            }
        }
    }

    suspend fun updateFlow(old: Imported) {
        try {
            // ProfileProcessor.update just re-downloaded the subscription; the Go
            // fetch persisted the response headers next to config.yaml
            // (fetch-headers.json). Re-requesting the panel here — the old OkHttp
            // GET — doubled traffic (panels count fetches against quota), could
            // race the primary download, and kept a second header-decoding path
            // alive. Absent snapshot = no HTTP response observed (content:// or a
            // failed fetch) — same early-return the old `!response.isSuccessful`
            // gave us.
            val headers = FetchHeadersFile.readFrom(context.importedDir.resolve(old.uuid.toString()))
                ?: return

            // Operator brand parses independently of subscription-userinfo —
            // panels that don't carry quota (free tiers, custom auth flows)
            // still need to brand the app. We do this BEFORE the
            // subscription-userinfo early-return so brand survives even
            // when the panel stops sending quota headers.
            //
            // parse(), NOT parseHttpHeaders(): the snapshot values are already
            // correct UTF-8 (Go passes header bytes through and JSON preserves
            // them). parseHttpHeaders' ISO-8859-1→UTF-8 recovery is only for
            // live transports and would mangle non-ASCII (Cyrillic brand /
            // display names) coming from the file.
            val brand = com.github.kr328.clash.common.branding.BrandManifestParser.parse { key ->
                headers.get(key)
            }
            // confirmedResponse=true: the header snapshot only exists for a fetch
            // that completed AND passed engine validation (update() succeeded), so
            // an empty brand here is a real "served the sub with no X-Brand-*
            // headers" signal (not a transient failure) and feeds the debounced
            // auto-clear.
            BrandRefresh.apply(context, old.uuid, brand, confirmedResponse = true)
            com.github.kr328.clash.service.util.OperatorPolicyHeaders.applyFromSnapshot(context, old.uuid, headers)

            // Re-derive the display name on EVERY update — not only when the panel sends
            // subscription-userinfo. Tying the rename to the quota header meant subscriptions
            // that don't send it never had their name corrected on update (the "белиберда /
            // URL-tail" name bug). Usage/expiry still come from subscription-userinfo when
            // present, but its absence must not skip the rename. (E-16)
            val usage = SubscriptionUsage.parse(headers.get("subscription-userinfo"))
            val renamed = deriveTitleFromHeaders(headers)
            val effectiveName = if (looksLikeGeneratedTokenName(old.name) && !renamed.isNullOrBlank()) {
                renamed
            } else {
                old.name
            }

            // Keep the previous quota/expiry when the panel didn't send subscription-userinfo
            // this time, instead of zeroing them.
            val new = Imported(
                old.uuid,
                effectiveName,
                old.type,
                old.source,
                old.interval,
                usage?.upload ?: old.upload,
                usage?.download ?: old.download,
                usage?.total ?: old.total,
                usage?.expireAt?.times(1000L) ?: old.expire,
                old.createdAt,
                old.profileOrder,
                ageSecretKey = old.ageSecretKey,
            )

            if (new != old) {
                ImportedDao().update(new)
                PendingDao().remove(new.uuid)
                context.sendProfileChanged(new.uuid)
            }
        } catch (e: Exception) {
            Log.w("updateFlow failed", e)
        }
    }

    // Delegate to the shared resolver so import and update decode headers identically (E-19):
    // title headers → Content-Disposition filename* (RFC-5987) → Subscription-Userinfo. Keep the
    // 64-char safety cap.
    private fun deriveTitleFromHeaders(headers: FetchHeadersFile): String? =
        SubscriptionNameGuesser.titleFromHeaders { key -> headers.get(key) }
            ?.let { if (it.length > 64) it.take(64) else it }

    private fun looksLikeGeneratedTokenName(name: String): Boolean {
        val n = name.trim()
        // Guard rails: only ever reconsider a medium-length single-word label — never rename a
        // human name with spaces/dots or a very short/long one.
        if (n.length !in 12..48) return false
        if (n.contains(' ') || n.contains('.') || n.contains('/')) return false
        // Shared predicate so import and update agree on what a token is (E-17): a name import
        // deliberately kept (e.g. all-lowercase "premiumplan1") is no longer replaced on update.
        return SubscriptionNameGuesser.looksLikeOpaqueToken(n)
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)

        scheduleUpdate(uuid, false)
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
        return resolveProfile(uuid)
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
            ordered.mapNotNull { uuid -> buildProfile(uuid, importedByUuid[uuid], pendingByUuid[uuid]) }
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
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }


    // The reads, editors and previews below live in the collaborators; kaidl needs every
    // IProfileManager method declared on this class, so each one just forwards.

    override suspend fun mergeRuleProviderYaml(
        uuid: UUID,
        ruleProvidersYaml: String,
        prependRuleLine: String,
    ): Boolean =
        overlay.mergeRuleProviderYaml(uuid, ruleProvidersYaml, prependRuleLine)

    override suspend fun previewMergeRuleProviderYaml(
        uuid: UUID,
        ruleProvidersYaml: String,
        prependRuleLine: String,
    ): String? =
        overlay.previewMergeRuleProviderYaml(uuid, ruleProvidersYaml, prependRuleLine)

    override suspend fun readProxyGroupsPreview(uuid: UUID): Map<String, ProxyGroupPreviewRow> =
        snapshots.readProxyGroupsPreview(uuid)

    override suspend fun readProxyTransports(uuid: UUID): Map<String, ProxyTransportInfo> =
        snapshots.readProxyTransports(uuid)

    override suspend fun readRuleProvidersYaml(uuid: UUID): String? =
        proxyConfig.readRuleProvidersYaml(uuid)

    override suspend fun readConfigScript(uuid: UUID): ConfigScriptState =
        overlay.readConfigScript(uuid)

    override suspend fun checkConfigScript(uuid: UUID, source: String): ConfigScriptFailure? =
        overlay.checkConfigScript(uuid, source)

    override suspend fun writeConfigScript(uuid: UUID, source: String, enabled: Boolean): ConfigScriptFailure? =
        overlay.writeConfigScript(uuid, source, enabled)

    override suspend fun replaceRuleProvidersYaml(uuid: UUID, yaml: String): Boolean =
        proxyConfig.replaceRuleProvidersYaml(uuid, yaml)

    override suspend fun previewReplaceRuleProvidersYaml(uuid: UUID, yaml: String): String? =
        proxyConfig.previewReplaceRuleProvidersYaml(uuid, yaml)

    override suspend fun readProxyProvidersYaml(uuid: UUID): String? =
        proxyConfig.readProxyProvidersYaml(uuid)

    override suspend fun replaceProxyProvidersYaml(uuid: UUID, yaml: String): Boolean =
        proxyConfig.replaceProxyProvidersYaml(uuid, yaml)

    override suspend fun previewReplaceProxyProvidersYaml(uuid: UUID, yaml: String): String? =
        proxyConfig.previewReplaceProxyProvidersYaml(uuid, yaml)

    override suspend fun appendRelayProxyGroup(uuid: UUID, groupName: String, providerKeys: List<String>): Boolean =
        proxyConfig.appendRelayProxyGroup(uuid, groupName, providerKeys)

    override suspend fun previewAppendRelayProxyGroup(
        uuid: UUID,
        groupName: String,
        providerKeys: List<String>,
    ): String? =
        proxyConfig.previewAppendRelayProxyGroup(uuid, groupName, providerKeys)

    override suspend fun removeProxyGroup(uuid: UUID, groupName: String): Boolean =
        proxyConfig.removeProxyGroup(uuid, groupName)

    override suspend fun previewRemoveProxyGroup(uuid: UUID, groupName: String): String? =
        proxyConfig.previewRemoveProxyGroup(uuid, groupName)

    override suspend fun setProxyDialerProxy(uuid: UUID, targetProxyName: String, dialerProxyName: String?): Boolean =
        proxyConfig.setProxyDialerProxy(uuid, targetProxyName, dialerProxyName)

    override suspend fun previewSetProxyDialerProxy(
        uuid: UUID,
        targetProxyName: String,
        dialerProxyName: String?,
    ): String? =
        proxyConfig.previewSetProxyDialerProxy(uuid, targetProxyName, dialerProxyName)

    override suspend fun listProxyDialerChains(uuid: UUID): List<String> =
        proxyConfig.listProxyDialerChains(uuid)

    override suspend fun clearAllProxyDialerChains(uuid: UUID): Boolean =
        proxyConfig.clearAllProxyDialerChains(uuid)

    override suspend fun previewClearAllProxyDialerChains(uuid: UUID): String? =
        proxyConfig.previewClearAllProxyDialerChains(uuid)

    override suspend fun readProxyProviderLabelsJson(uuid: UUID): String? =
        proxyConfig.readProxyProviderLabelsJson(uuid)

    override suspend fun writeProxyProviderLabelsJson(uuid: UUID, json: String): Boolean =
        proxyConfig.writeProxyProviderLabelsJson(uuid, json)

    override suspend fun readRuleState(uuid: UUID): String? =
        overlay.readRuleState(uuid)

    override suspend fun applyRuleState(uuid: UUID, stateJson: String): Boolean =
        overlay.applyRuleState(uuid, stateJson)

    override suspend fun queryDnsHostsConfigJson(uuid: UUID): String? =
        overlay.queryDnsHostsConfigJson(uuid)

    override suspend fun previewSetDnsHosts(uuid: UUID, configJson: String): String? =
        overlay.previewSetDnsHosts(uuid, configJson)

    override suspend fun isDnsHostsManaged(uuid: UUID): Boolean =
        overlay.isDnsHostsManaged(uuid)

    override suspend fun setDnsHostsManaged(uuid: UUID, managed: Boolean) {
        overlay.setDnsHostsManaged(uuid, managed)
    }

    override suspend fun queryTunnelsConfigJson(uuid: UUID): String? =
        overlay.queryTunnelsConfigJson(uuid)

    override suspend fun previewSetTunnels(uuid: UUID, configJson: String): String? =
        overlay.previewSetTunnels(uuid, configJson)

    override suspend fun isTunnelsManaged(uuid: UUID): Boolean =
        overlay.isTunnelsManaged(uuid)

    override suspend fun setTunnelsManaged(uuid: UUID, managed: Boolean) {
        overlay.setTunnelsManaged(uuid, managed)
    }

    override suspend fun previewRuleStateYaml(uuid: UUID, stateJson: String): String? =
        overlay.previewRuleStateYaml(uuid, stateJson)

    override suspend fun readRuleEditorBundle(uuid: UUID): String? =
        overlay.readRuleEditorBundle(uuid)

    override suspend fun applyYamlPreview(previewId: String): Boolean =
        previews.applyYamlPreview(previewId)

    override suspend fun readProxyEntryYaml(uuid: UUID, proxyName: String): String? =
        snapshots.readProxyEntryYaml(uuid, proxyName)

    override suspend fun readSubscriptionChainNodes(uuid: UUID): List<String> =
        overlay.readSubscriptionChainNodes(uuid)

    override suspend fun readSubscriptionChain(uuid: UUID): String? =
        overlay.readSubscriptionChain(uuid)

    override suspend fun previewSubscriptionChain(
        uuid: UUID,
        firstProfile: UUID,
        firstProxy: String,
        exitProfile: UUID,
        exitProxy: String,
    ): String? =
        overlay.previewSubscriptionChain(uuid, firstProfile, firstProxy, exitProfile, exitProxy)

    override suspend fun previewRemoveSubscriptionChain(uuid: UUID): String? =
        overlay.previewRemoveSubscriptionChain(uuid)

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
                com.github.kr328.clash.service.util.XDriveSecretRedactor.redactConfigYaml(file.readText())
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

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)
        return buildProfile(uuid, imported, pending)
    }

    /**
     * Merge the imported + pending rows for [uuid] into a [Profile]. Pure (no DB) so callers that
     * already loaded the rows in bulk (see [queryAll]) avoid an N+1 of per-uuid queries.
     */
    private fun buildProfile(uuid: UUID, imported: Imported?, pending: Pending?): Profile? {
        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = pending?.upload ?: imported?.upload ?: return null
        val download = pending?.download ?: imported?.download ?: return null
        val total = pending?.total ?: imported?.total ?: return null
        val expire = pending?.expire ?: imported?.expire ?: return null

        return Profile(
            uuid,
            name,
            type,
            source,
            active != null && imported?.uuid == active,
            interval,
            upload,
            download,
            total,
            expire,
            resolveUpdatedAt(uuid),
            imported != null,
            pending != null,
            ageSecretKey = pending?.ageSecretKey ?: imported?.ageSecretKey,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        context.pendingDir.resolve(uuid.toString()).directoryLastModified?.let { return it }
        val importedDir = context.importedDir.resolve(uuid.toString())
        // subscription.yaml is written only when the subscription is fetched / committed; the
        // directory-wide max also caught config.yaml, which is re-composed on every VPN start and
        // mode switch, so "last updated" drifted to "last connected".
        File(importedDir, ProfileComposer.SUBSCRIPTION_FILE).takeIf { it.isFile }
            ?.let { return it.lastModified() }
        return importedDir.directoryLastModified ?: -1
    }

    private suspend fun nextProfileOrder(): Long {
        val importedMax = ImportedDao().queryMaxProfileOrder() ?: -1L
        val pendingMax = PendingDao().queryMaxProfileOrder() ?: -1L
        return maxOf(importedMax, pendingMax) + 1L
    }

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists())
            throw FileNotFoundException("profile $source not found")

        t.deleteRecursively()

        s.copyRecursively(t)
    }

    private suspend fun scheduleUpdate(uuid: UUID, startImmediately: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        if (startImmediately) {
            ProfileReceiver.schedule(context, imported)
        } else {
            ProfileReceiver.scheduleNext(context, imported)
        }
    }
}
