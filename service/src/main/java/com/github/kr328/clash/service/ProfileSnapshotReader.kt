package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProfileSnapshot
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.model.ProxyTransportInfo
import com.github.kr328.clash.service.util.PreviewResourceLimits
import com.github.kr328.clash.service.util.ProxyGroupsYamlPreview
import com.github.kr328.clash.service.util.ProxyTransportYamlPreview
import com.github.kr328.clash.service.util.ProxyYamlPreview
import com.github.kr328.clash.service.util.importedDir
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Read side of an imported profile's `config.yaml`: the parsed-snapshot and engine-resolved
 * group-preview caches the dashboard ticker leans on, and the reads built on them.
 */
internal class ProfileSnapshotReader(private val context: Context) {
    /**
     * In-memory cache of parsed [ProfileSnapshot] keyed by profile UUID.
     * The dashboard ticker re-queries readProxyGroupsPreview /
     * readProxyTransports / readProxyEntryYaml on every tick — without
     * caching, each tick re-runs mihomo's full ParseRawConfig pipeline
     * over a multi-MB config.yaml, which is the dominant cost on the
     * service IPC path for users with heavy subscriptions.
     *
     * Invalidation is automatic via the config.yaml lastModified
     * timestamp — subscription updates / re-imports rewrite the file,
     * the next reader sees a fresh mtime and re-parses. The entry
     * itself only needs explicit removal on profile delete (memory
     * hygiene; nothing else reads it after deletion).
     *
     * Access guarded by [snapshotCacheLock]. Parse runs *outside* the
     * lock so concurrent readers of different profiles never block on
     * each other — a race between two readers of the same profile
     * just causes a redundant parse (parse is idempotent, last writer
     * to the map wins).
     */
    private data class CachedSnapshot(val lastModified: Long, val snapshot: ProfileSnapshot)
    private val snapshotCache = LinkedHashMap<UUID, CachedSnapshot>()
    private val snapshotCacheLock = Any()

    /**
     * In-memory cache of the engine-resolved proxy-group preview keyed by
     * profile UUID, mirroring [snapshotCache]. [engineProxyGroupsPreview] runs
     * mihomo's full ParseRawConfig over config.yaml through JNI — far heavier
     * than the snapshot parse — and the dashboard ticker calls it every ~2s per
     * previewed profile, so without this the resolve dominated CPU/battery on
     * heavy subscriptions while the app was open. Invalidated by config.yaml
     * lastModified like the snapshot; the `null` result (engine could not
     * parse → Kotlin fallback) is cached too, so an unparsable config is not
     * re-resolved every tick.
     */
    private data class CachedEnginePreview(
        val lastModified: Long,
        val preview: Map<String, ProxyGroupPreviewRow>?,
    )
    private val enginePreviewCache = LinkedHashMap<UUID, CachedEnginePreview>()
    private val enginePreviewCacheLock = Any()

    companion object {
        // Power users may juggle a handful of subscriptions; keep enough
        // headroom that switching between them stays hot, but bound the
        // total memory cost — parsed snapshots can reach a few hundred KB
        // on heavy configs.
        private const val SNAPSHOT_CACHE_MAX = 4
    }

    /**
     * Return a parsed ProfileSnapshot for [configFile], reusing the cached
     * one when the file's lastModified hasn't advanced. Callers can hand
     * this to YAML preview helpers (ProxyGroupsYamlPreview /
     * ProxyTransportYamlPreview / ProxyYamlPreview) without re-running
     * mihomo's parser on every dashboard tick.
     */
    fun getOrParseSnapshot(uuid: UUID, configFile: File): ProfileSnapshot {
        val lastMod = configFile.lastModified()
        synchronized(snapshotCacheLock) {
            val cached = snapshotCache[uuid]
            if (cached != null && cached.lastModified == lastMod) {
                // LRU touch — promote to most-recently-used.
                snapshotCache.remove(uuid)
                snapshotCache[uuid] = cached
                return cached.snapshot
            }
        }
        val parsed = Clash.parseProfileSnapshot(configFile.parentFile!!)
        synchronized(snapshotCacheLock) {
            snapshotCache[uuid] = CachedSnapshot(lastMod, parsed)
            while (snapshotCache.size > SNAPSHOT_CACHE_MAX) {
                snapshotCache.remove(snapshotCache.keys.first())
            }
        }
        return parsed
    }

    fun invalidateSnapshotCache(uuid: UUID) {
        synchronized(snapshotCacheLock) {
            snapshotCache.remove(uuid)
        }
        synchronized(enginePreviewCacheLock) {
            enginePreviewCache.remove(uuid)
        }
    }

    suspend fun readProxyGroupsPreview(uuid: UUID): Map<String, ProxyGroupPreviewRow> {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext emptyMap()
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile || file.length() > PreviewResourceLimits.MAX_CONFIG_BYTES) {
                return@withContext emptyMap()
            }
            try {
                val snapshot = getOrParseSnapshot(uuid, file)
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
                engineProxyGroupsPreview(uuid, file, snapshot)
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

    /**
     * Engine-resolved membership for the offline preview, or null when mihomo cannot parse the
     * config (caller falls back to the Kotlin preview).
     *
     * `staticProxies` still comes from the snapshot: it must stay the *declared* `proxies:` list,
     * never the expanded one, because the picker's 1-hop heuristic uses it to tell a pure dispatch
     * shell from a group whose members are flat only because of `include-all`.
     */
    /**
     * Cache wrapper over [engineProxyGroupsPreviewUncached]: reuses the resolved preview while
     * config.yaml's lastModified is unchanged, so the dashboard ticker's ~2s cadence no longer
     * re-runs mihomo's full parse on every tick (the 0.10.1 battery regression). Both a resolved
     * map AND a `null` (engine could not parse) are cached — an unparsable config falls back to
     * the Kotlin preview once, not every tick.
     */
    private fun engineProxyGroupsPreview(
        uuid: UUID,
        file: File,
        snapshot: ProfileSnapshot,
    ): Map<String, ProxyGroupPreviewRow>? {
        val lastMod = file.lastModified()
        synchronized(enginePreviewCacheLock) {
            val cached = enginePreviewCache[uuid]
            if (cached != null && cached.lastModified == lastMod) {
                // LRU touch — promote to most-recently-used.
                enginePreviewCache.remove(uuid)
                enginePreviewCache[uuid] = cached
                return cached.preview
            }
        }
        val computed = engineProxyGroupsPreviewUncached(file, snapshot)
        synchronized(enginePreviewCacheLock) {
            enginePreviewCache[uuid] = CachedEnginePreview(lastMod, computed)
            while (enginePreviewCache.size > SNAPSHOT_CACHE_MAX) {
                enginePreviewCache.remove(enginePreviewCache.keys.first())
            }
        }
        return computed
    }

    private fun engineProxyGroupsPreviewUncached(
        file: File,
        snapshot: ProfileSnapshot,
    ): Map<String, ProxyGroupPreviewRow>? {
        val resolved = runCatching { Clash.resolveProxyGroups(file.readText()) }.getOrNull()
            ?: return null

        val declaredByName = HashMap<String, List<String>>(snapshot.proxyGroups.size)
        for (group in snapshot.proxyGroups) {
            val name = (group["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) continue
            declaredByName[name] = (group["proxies"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        }

        return resolved.associate { group ->
            group.name to ProxyGroupPreviewRow(
                type = engineGroupType(group.type),
                members = group.all,
                hidden = group.hidden,
                staticProxies = declaredByName[group.name].orEmpty(),
            )
        }
    }

    /** mihomo marshals adapter types as `Selector` / `URLTest` / …; YAML spells them `url-test`. */
    private fun engineGroupType(raw: String): Proxy.Type = when (raw.trim().lowercase()) {
        "selector", "select" -> Proxy.Type.Selector
        "fallback" -> Proxy.Type.Fallback
        "urltest", "url-test" -> Proxy.Type.URLTest
        "loadbalance", "load-balance" -> Proxy.Type.LoadBalance
        "relay" -> Proxy.Type.Relay
        else -> Proxy.Type.Selector
    }

    suspend fun readProxyTransports(uuid: UUID): Map<String, ProxyTransportInfo> {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext emptyMap()
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile || file.length() > PreviewResourceLimits.MAX_CONFIG_BYTES) {
                return@withContext emptyMap()
            }
            try {
                val snapshot = getOrParseSnapshot(uuid, file)
                ProxyTransportYamlPreview.parse(snapshot, file.parentFile)
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    suspend fun readProxyEntryYaml(uuid: UUID, proxyName: String): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) {
                return@withContext null
            }
            val file = File(context.importedDir, "$uuid/config.yaml")
            if (!file.isFile) {
                return@withContext null
            }
            try {
                val snapshot = getOrParseSnapshot(uuid, file)
                ProxyYamlPreview.extractProxyEntry(snapshot, proxyName)
            } catch (_: Exception) {
                null
            }
        }
    }
}
