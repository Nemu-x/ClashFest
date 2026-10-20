package com.github.kr328.clash.main

import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.StandalonePing
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.model.ServerPingMode
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.remote.IProxyDelayObserver
import com.github.kr328.clash.util.DirectServerPing
import com.github.kr328.clash.util.ProfileRuntimeTarget
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import java.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Latency probing and runtime proxy-group walking for the Home screen: per-proxy health
 * checks streamed into the design, offline (engine-less) node pings, the lazy group warm-up
 * and the group-tree / group-detail queries the dashboard fetches build on.
 */
internal class MainProxyProbe(private val activity: MainActivity) {
    private val uiStore get() = activity.store
    private val design get() = activity.currentDesign

    /** Wait until the core has proxy groups (profile loaded), up to ~7s. */
    suspend fun waitForProxyEngineReady() {
        repeat(35) {
            val ok = runCatching {
                withClash { queryProxyGroupNames(false).isNotEmpty() }
            }.getOrDefault(false)
            if (ok) return
            delay(200L)
        }
    }

    /**
     * Run a per-proxy health check on [group] and stream each proxy's delay
     * into the active card as soon as URLTest resolves. Suspends until every
     * proxy has reported (or the call early-outed because the group is
     * missing / wrong type).
     *
     * UI patches are dispatched on the activity's MainScope and target the
     * design property — if the activity has already torn down the design by
     * the time a result lands, the patch is silently dropped. Callers that
     * need `now` / `alive` state should still issue a closing
     * refreshRuntimeGroupDetails — per-proxy push only carries the delay
     * measurement itself.
     */
    suspend fun runPerProxyHealthCheck(group: String, testUrl: String = "") {
        runCatching {
            withClash {
                healthCheckPerProxy(
                    group,
                    testUrl,
                    object : IProxyDelayObserver {
                        override fun onDelay(
                            grp: String,
                            proxy: String,
                            delayMs: Int,
                            errMsg: String,
                        ) {
                            // Failed URLTest carries errMsg + delayMs == 0. Showing 0 would
                            // render as "0ms" — engine convention is Int.MAX_VALUE for
                            // unreachable, so coerce before the patch.
                            val effective = if (errMsg.isNotEmpty()) Int.MAX_VALUE else delayMs
                            activity.launch {
                                design?.patchSingleProxyDelay(grp, proxy, effective)
                            }
                        }

                        override fun onComplete(error: String?) {
                            // No-op: the outer suspend returns via await() in
                            // ClashManager.healthCheckPerProxy.
                        }
                    },
                )
            }
        }
    }

    /**
     * Single-node twin of [runPerProxyHealthCheck]: one URLTest for [proxy] inside [group], result
     * pushed through the same per-proxy patch. A failed lookup (proxy not found) clears the
     * capsule's pending state instead of leaving "…" behind.
     */
    suspend fun runSingleProxyHealthCheck(group: String, proxy: String) {
        val ok = runCatching {
            withClash {
                healthCheckProxy(
                    group,
                    proxy,
                    "",
                    object : IProxyDelayObserver {
                        override fun onDelay(
                            grp: String,
                            proxyName: String,
                            delayMs: Int,
                            errMsg: String,
                        ) {
                            val effective = if (errMsg.isNotEmpty()) Int.MAX_VALUE else delayMs
                            Log.d("single ping: $grp / $proxyName -> ${delayMs}ms err='$errMsg'")
                            activity.launch {
                                design?.patchSingleProxyDelay(grp, proxyName, effective)
                            }
                        }

                        override fun onComplete(error: String?) {
                            // The outer suspend returns via await() in ClashManager.healthCheckProxy.
                            if (error != null) Log.w("single ping: $group / $proxy failed: $error")
                        }
                    },
                )
            }
        }.onFailure { e -> Log.w("single ping: $group / $proxy threw", e) }.isSuccess
        if (!ok) design?.clearNodePingPending(proxy)
    }

    /** Groups already warmed up this session, so a group is url-tested at most once (O-07). */
    private val warmedGroups = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val directPingSlots = kotlinx.coroutines.sync.Semaphore(4)

    fun clearWarmedGroups() {
        warmedGroups.clear()
    }

    /**
     * Ping one node without the running engine. ThroughServer: a GET through the node from the core
     * (the node is built from its YAML alone, nothing is loaded). ToServer: a TCP connect to it.
     */
    suspend fun measureOffline(mode: ServerPingMode, profile: Profile, name: String, testUrl: String): Int =
        directPingSlots.withPermit {
            if (StandalonePing.isBuiltinProxyName(name)) return@withPermit -1
            val yaml = withProfile { readProxyEntryYaml(profile.uuid, name) } ?: return@withPermit -1
            when (mode) {
                ServerPingMode.ThroughServer -> withClash { urlTestProxyYaml(yaml, testUrl) }
                ServerPingMode.ToServer -> withContext(Dispatchers.IO) { DirectServerPing.measure(activity, yaml) }
            }
        }

    /**
     * Warm up (url-test) a single group and its nested auto subtree — once per session. This is the
     * lazy replacement for the old blanket connect-time warmup that url-tested EVERY auto group;
     * on a heavy subscription (200-node url-test groups) that saturated the engine on connect, which
     * showed up as an "infinite ping" and made proxy switching sluggish (O-07). We now test only the
     * group the user actually looks at (see [warmVisibleGroup]); the
     * subtree walk still covers kaso.fyi-style layouts where the visible root is a plain select over
     * a `hidden: true` url-test subtree.
     */
    suspend fun warmUpGroupSubtree(rootGroup: String): Set<String> = coroutineScope {
        if (rootGroup.isBlank() || !warmedGroups.add(rootGroup)) return@coroutineScope emptySet()
        waitForProxyEngineReady()

        val subtree = collectRuntimeGroupTree(rootGroup).ifEmpty { setOf(rootGroup) }
        val autoGroups = linkedSetOf<String>()
        for (groupName in subtree) {
            val group = runCatching {
                withClash { queryProxyGroup(groupName, com.github.kr328.clash.core.model.ProxySort.Default) }
            }.getOrNull() ?: continue
            if (isAutoProxyGroup(groupName, group)) {
                autoGroups += groupName
            }
        }
        if (autoGroups.isEmpty()) return@coroutineScope emptySet()

        // Prime engine state so per-proxy push has rows to land on. Without this, the first warmup
        // cycle would have nowhere to write — proxyDetails is empty until the user scrolls in.
        val primed = refreshRuntimeGroupDetails(autoGroups)
        if (primed.isNotEmpty()) {
            design?.patchProxyDetails(primed)
        }

        autoGroups.map { group ->
            launch { runPerProxyHealthCheck(group) }
        }.joinAll()
        autoGroups.toSet()
    }

    suspend fun collectRuntimeGroupTree(rootGroup: String): Set<String> {
        if (rootGroup.isBlank()) return emptySet()
        // Include hidden groups too — manual ping for a subscription whose
        // entire url-test/fallback subtree is hidden (visible root is select)
        // would otherwise stop at the root and miss every auto child.
        val knownGroups = runCatching { withClash { queryAllProxyGroupNamesIncludingHidden().toSet() } }
            .getOrDefault(emptySet())
        val seen = linkedSetOf<String>()

        suspend fun visit(groupName: String) {
            if (groupName.isBlank() || groupName in seen) return
            val group = runCatching {
                withClash { queryProxyGroup(groupName, uiStore.proxySort) }
            }.getOrNull() ?: return
            seen += groupName
            for (proxy in group.proxies) {
                if (proxy.type.group || proxy.name in knownGroups) {
                    visit(proxy.name)
                }
            }
        }

        visit(rootGroup)
        return seen
    }

    suspend fun refreshRuntimeGroupDetails(groups: Set<String>): Map<String, ProxyGroup> {
        if (groups.isEmpty()) return emptyMap()
        val refreshed = LinkedHashMap<String, ProxyGroup>()
        for (group in groups) {
            val detail = runCatching {
                withClash { queryProxyGroup(group, uiStore.proxySort) }
            }.getOrNull() ?: continue
            refreshed[group] = detail
        }
        return refreshed
    }

    /**
     * Lazy warmup (O-07): url-test [group]'s subtree once, only when the user actually opens it —
     * instead of every auto group up front on connect.
     */
    fun warmVisibleGroup(scope: CoroutineScope, design: MainDesign, group: String) {
        scope.launch {
            try {
                val warmed = warmUpGroupSubtree(group)
                if (warmed.isNotEmpty()) {
                    val patches = refreshRuntimeGroupDetails(warmed)
                    if (patches.isNotEmpty()) design.patchProxyDetails(patches)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** "Ping all" for one subscription: through the live engine when it serves [profile], offline otherwise. */
    fun pingProfileNodes(
        scope: CoroutineScope,
        design: MainDesign,
        profile: Profile,
        group: String,
        nodeNames: List<String>,
        testUrl: String,
        scheduleRefresh: () -> Unit,
    ) {
        scope.launch {
            try {
                val pingMode = uiStore.serverPingMode
                design.setPingingProfile(profile.uuid)
                design.clearStandalonePingForProfile(profile.uuid)
                val engineReady = pingMode == ServerPingMode.ThroughServer && runCatching {
                    activity.resolveStatusSnapshot().serviceRunning &&
                        withClash { queryProxyGroupNames(false).isNotEmpty() }
                }.getOrDefault(false)
                val activeUuid = if (pingMode == ServerPingMode.ThroughServer) withProfile { queryActive()?.uuid } else null
                if (pingMode == ServerPingMode.ThroughServer &&
                    ProfileRuntimeTarget.canUseEngine(engineReady, activeUuid, profile.uuid)
                ) {
                    waitForProxyEngineReady()
                    val groupsToRefresh = collectRuntimeGroupTree(group)
                        .ifEmpty { linkedSetOf(group).filter { it.isNotBlank() }.toSet() }
                    // Prime live engine state so per-proxy push has rows to land on.
                    // healthCheckPerProxy only updates delays for proxies already
                    // present in proxyDetails — without an initial query the very
                    // first ping cycle would be a silent no-op until the closing
                    // refresh below.
                    val primed = refreshRuntimeGroupDetails(groupsToRefresh)
                    if (primed.isNotEmpty()) {
                        design.patchProxyDetails(primed)
                    }
                    val jobs = groupsToRefresh.map { groupName ->
                        launch { runPerProxyHealthCheck(groupName, testUrl) }
                    }
                    jobs.forEach { it.join() }
                    // Closing refresh captures `now` / `alive` fields the per-proxy
                    // push doesn't carry, plus any auto-group selection that the
                    // engine's url-test logic flipped after the ping cycle.
                    val refreshed = refreshRuntimeGroupDetails(groupsToRefresh)
                    if (refreshed.isNotEmpty()) {
                        design.patchProxyDetails(refreshed)
                    }
                    scheduleRefresh()
                } else {
                    // VPN off or another subscription connected: ThroughServer pings each
                    // node with a GET through it from the core, ToServer is a TCP connect.
                    val offlineGroups = withProfile { readProxyGroupsPreview(profile.uuid) }
                    val pingTargets = collectOfflineLeafProxyNames(group, nodeNames, offlineGroups)
                    val jobs = pingTargets.map { name -> launch(Dispatchers.IO) {
                        val ms = measureOffline(pingMode, profile, name, testUrl)
                        design.patchStandalonePingResults(profile.uuid, mapOf(name to ms))
                    } }
                    jobs.forEach { it.join() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                design.showExceptionToast(e)
            } finally {
                design.setPingingProfile(null)
            }
        }
    }

    /** Single-node ping: the live engine when it serves [profile], offline otherwise. */
    fun pingNode(scope: CoroutineScope, design: MainDesign, profile: Profile, group: String, proxy: String) {
        scope.launch {
            try {
                val pingMode = uiStore.serverPingMode
                val engineReady = pingMode == ServerPingMode.ThroughServer && runCatching {
                    activity.resolveStatusSnapshot().serviceRunning &&
                        withClash { queryProxyGroupNames(false).isNotEmpty() }
                }.getOrDefault(false)
                val activeUuid = if (pingMode == ServerPingMode.ThroughServer) withProfile { queryActive()?.uuid } else null
                Log.d("single ping: request $group / $proxy engineReady=$engineReady active=$activeUuid profile=${profile.uuid}")
                if (pingMode == ServerPingMode.ThroughServer &&
                    ProfileRuntimeTarget.canUseEngine(engineReady, activeUuid, profile.uuid)
                ) {
                    runSingleProxyHealthCheck(group, proxy)
                } else {
                    val ms = measureOffline(pingMode, profile, proxy, "")
                    design.patchStandalonePingResults(profile.uuid, mapOf(proxy to ms))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                design.clearNodePingPending(proxy)
                design.showExceptionToast(e)
            }
        }
    }
}

private fun isAutoProxyGroup(name: String, group: ProxyGroup): Boolean {
    if (group.type == Proxy.Type.URLTest ||
        group.type == Proxy.Type.Fallback ||
        group.type == Proxy.Type.LoadBalance
    ) {
        return true
    }

    val normalized = name.lowercase(Locale.ROOT)
    return normalized.contains("auto") ||
        normalized.contains("url-test") ||
        normalized.contains("best") ||
        normalized.contains("fast") ||
        normalized.contains("авто") ||
        normalized.contains("лучш") ||
        normalized.contains("быстр")
}

private fun collectOfflineLeafProxyNames(
    rootGroup: String,
    directNames: List<String>,
    groups: Map<String, ProxyGroupPreviewRow>,
): List<String> {
    val leaves = linkedSetOf<String>()
    val seenGroups = linkedSetOf<String>()

    fun visitName(name: String) {
        if (name.isBlank()) return
        val nested = groups[name]?.members
        if (nested == null) {
            leaves += name
            return
        }
        if (!seenGroups.add(name)) return
        nested.forEach(::visitName)
    }

    if (rootGroup.isNotBlank() && rootGroup in groups) {
        visitName(rootGroup)
    } else {
        directNames.forEach(::visitName)
    }
    return leaves.toList()
}
