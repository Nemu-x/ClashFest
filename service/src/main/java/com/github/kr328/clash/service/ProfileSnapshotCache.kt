package com.github.kr328.clash.service

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProfileSnapshot
import com.github.kr328.clash.core.model.Proxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import java.io.File
import java.util.*

/**
 * Owns [ProfileManager]'s in-memory parse caches: the [ProfileSnapshot] per profile and the
 * engine-resolved proxy-group preview, both keyed by profile UUID and invalidated by the
 * config.yaml lastModified timestamp.
 */
internal class ProfileSnapshotCache {
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

    /**
     * Return a parsed ProfileSnapshot for [configFile], reusing the cached
     * one when the file's lastModified hasn't advanced. Callers can hand
     * this to YAML preview helpers (ProxyGroupsYamlPreview /
     * ProxyTransportYamlPreview / ProxyYamlPreview) without re-running
     * mihomo's parser on every dashboard tick.
     */
    internal fun getOrParseSnapshot(uuid: UUID, configFile: File): ProfileSnapshot {
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

    internal fun invalidateSnapshotCache(uuid: UUID) {
        synchronized(snapshotCacheLock) {
            snapshotCache.remove(uuid)
        }
        synchronized(enginePreviewCacheLock) {
            enginePreviewCache.remove(uuid)
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
    internal fun engineProxyGroupsPreview(
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
        "chain" -> Proxy.Type.Chain
        else -> Proxy.Type.Selector
    }

    companion object {
        // Power users may juggle a handful of subscriptions; keep enough
        // headroom that switching between them stays hot, but bound the
        // total memory cost — parsed snapshots can reach a few hundred KB
        // on heavy configs.
        private const val SNAPSHOT_CACHE_MAX = 4
    }
}
