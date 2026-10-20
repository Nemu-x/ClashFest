package com.github.kr328.clash.design.adapter

import android.content.Context
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ServerPingMode
import com.github.kr328.clash.service.model.Profile
import java.util.UUID

/**
 * Mirrors the engine's own visibility rule for "Hide non-selectable groups"
 * (`proxyGroupVisibleWithSelectableFilter`, native/tunnel/proxies.go): url-test, load-balance
 * and relay groups drop out, while Selector and Fallback stay — nested fallback chains are
 * common in subscription layouts.
 *
 * Kept in sync deliberately. The engine list is queried WITH the user's setting, so without
 * this the offline preview showed auto groups that vanished the instant the tunnel came up,
 * and the pill bar visibly reflowed on connect.
 */
internal fun ProfileAdapter.isFilteredOutAsNotSelectable(type: Proxy.Type): Boolean =
    excludeNotSelectable && type != Proxy.Type.Selector && type != Proxy.Type.Fallback

/** Engine data applies only when the expanded card is the active profile and VPN is on. */
internal fun ProfileAdapter.useEngineFor(profile: Profile): Boolean =
    clashRunning &&
        profile.active &&
        profile.uuid == activeProfileUuid &&
        proxyGroupNames.isNotEmpty()

internal fun ProfileAdapter.effectiveGroupsForProfile(profile: Profile): List<String> {
    if (profile.uuid !in expandedUuids || !profile.imported) {
        return emptyList()
    }
    // proxyGroupNames is the visible-only list (from queryProxyGroupNames).
    // offlinePreviewByProfile keys include hidden too (parseProxyGroupsPreview
    // is called with includeHidden=true), so we filter back to visible here
    // and then narrowly surface a hidden auto-subgroup only when the
    // visible parent is a pure dispatch shell — i.e. all of its declared
    // members are themselves group names, none are dialable leaf nodes.
    // That catches the kaso.fyi pattern (one visible select root whose
    // only members are hidden url-test/fallback subgroups) without
    // polluting the pill bar of typical mixed configs that legitimately
    // reference hidden auto groups as backup paths alongside leaf nodes.
    val offlinePreview = offlinePreviewByProfile[profile.uuid]
    val visible = if (useEngineFor(profile)) {
        proxyGroupNames
    } else {
        offlinePreview?.entries
            ?.filterNot { (_, row) -> row.hidden || isFilteredOutAsNotSelectable(row.type) }
            ?.map { (k, _) -> k }
            ?.toList()
            .orEmpty()
    }
    if (offlinePreview.isNullOrEmpty()) return visible

    val visibleSet = visible.toHashSet()
    // Global pure-shell test: every visible root must reference *only*
    // other proxy-groups (no leaves, no special targets like DIRECT /
    // REJECT). That uniquely identifies a kaso-style config whose top
    // level is a dispatch tree delegating routing to hidden auto
    // subgroups. A standard subscription will have at least one visible
    // root with concrete leaf nodes in its `proxies:`, which fails the
    // shell test and short-circuits the heuristic — its hidden auto
    // backups stay hidden, which is what the user expects.
    val configIsPureShell = visible.isNotEmpty() && visible.all { vname ->
        val row = uniqueGroupMatch(offlinePreview, vname)
            ?: return@all false
        val staticRefs = row.staticProxies
        if (staticRefs.isEmpty()) return@all false
        staticRefs.all { memberName ->
            offlinePreview[memberName] != null ||
                offlinePreview.entries.any { groupsMatchKey(memberName, it.key) }
        }
    }
    if (!configIsPureShell) return visible

    val extras = LinkedHashSet<String>()
    for (vname in visible) {
        val row = uniqueGroupMatch(offlinePreview, vname)
            ?: continue
        for (memberName in row.staticProxies) {
            if (memberName in visibleSet || memberName in extras) continue
            val memberRow = uniqueGroupMatch(offlinePreview, memberName)
                ?: continue
            if (!memberRow.hidden) continue
            // Only auto types help routing — Selector/Unknown hidden roots
            // (e.g. mihomo's auto GLOBAL when the config defines its own)
            // would just add noise.
            if (memberRow.type != Proxy.Type.URLTest &&
                memberRow.type != Proxy.Type.Fallback &&
                memberRow.type != Proxy.Type.LoadBalance
            ) continue
            extras.add(memberName)
        }
    }
    return if (extras.isEmpty()) visible else visible + extras.toList()
}

internal fun ProfileAdapter.groupsForSelectionSummary(profile: Profile): List<String> {
    if (!profile.imported) return emptyList()
    return if (useEngineFor(profile)) {
        proxyGroupNames
    } else {
        offlinePreviewByProfile[profile.uuid]?.keys?.toList().orEmpty()
    }
}

internal fun ProfileAdapter.selectedGroupForSummary(profile: Profile): String? {
    val groups = groupsForSelectionSummary(profile)
    if (groups.isEmpty()) return null
    val uuid = profile.uuid
    primaryGroupIn(profile, groups)?.let { return it }
    val kept = selectedGroupIndex[uuid]?.takeIf { it in groups.indices }?.let { groups[it] }
    if (kept != null) {
        return kept
    }
    val picked = resolvePreferredGroupFromList(profile, groups)
    val index = groups.indexOf(picked).takeIf { it >= 0 } ?: 0
    selectedGroupIndex[uuid] = index.coerceIn(0, groups.lastIndex)
    return groups[selectedGroupIndex[uuid]!!]
}

/** The operator's primary group as named in [groups], when it applies to [profile]. */
internal fun ProfileAdapter.primaryGroupIn(profile: Profile, groups: List<String>): String? {
    val primary = primaryProxyGroup ?: return null
    if (profile.uuid != activeProfileUuid || tunnelMode == TunnelState.Mode.Global) return null
    return groups.firstOrNull { groupsMatchKey(it, primary) }
}

internal fun ProfileAdapter.formatSelectionSummaryForHome(groupName: String): String = displayGroupName(groupName)

internal fun ProfileAdapter.formatSelectionSummaryForProfiles(
    context: Context,
    profile: Profile,
    groupName: String,
): String {
    val groupDisplay = displayGroupName(groupName)
    val selectedProxy = resolvedSelectedProxyName(profile, groupName)
        ?.let(::displayGroupName)
    return if (selectedProxy.isNullOrBlank()) {
        context.getString(R.string.main_selected_group_fmt, groupDisplay)
    } else {
        context.getString(R.string.main_selected_route_fmt, groupDisplay, selectedProxy)
    }
}

/**
 * Resolve a leaf node's protocol type **offline** from the parsed transport info
 * (config-overlay: config.yaml is the composed effective config). Without this the
 * picker shows no protocol/transport badge unless the engine is running — the type
 * was hard-coded to Unknown for offline placeholders. Empty/absent → Unknown.
 */
internal fun ProfileAdapter.offlineProxyType(uuid: UUID, name: String): Proxy.Type {
    val leaf = proxyTypeFromYamlName(transportInfoByProfile[uuid]?.get(name)?.type)
    if (leaf != Proxy.Type.Unknown) return leaf
    // Not a leaf proxy → it may be a nested auto-group; surface its group type
    // (url-test / fallback / …) so the picker can chip it distinctly offline too.
    return offlinePreviewByProfile[uuid]?.get(name)?.type ?: Proxy.Type.Unknown
}

internal fun ProfileAdapter.proxyGroupForRow(profile: Profile, groupName: String): ProxyGroup? {
    if (useEngineFor(profile)) {
        val live = uniqueGroupMatch(proxyDetails, groupName)
        if (live != null) {
            // The engine is authoritative: Clash.queryGroup() -> tunnel.QueryProxyGroup calls
            // mihomo's g.Proxies(), which has ALREADY expanded `include-all*` / `use:` and applied
            // `filter` / `exclude-filter` / `exclude-type`.
            //
            // We used to union the offline YAML preview into the live group whenever the offline
            // member count was larger, on the assumption that queryGroup() returned only the
            // statically declared `proxies:`. That assumption is stale, and the heuristic fired on
            // cardinality alone — so whenever the offline preview over-counted (e.g. it cannot
            // evaluate a `filter` containing a quantifier or lookahead and silently falls back to
            // the declared list), nodes the engine had deliberately excluded were re-injected into
            // the connected view. Same class of bug as the provider fallback fixed on the Go side
            // in tunnel/proxies.go (gated on hasLeaf). Trust the engine; never widen its answer.
            return live.withSelectionOverlay(this, profile.uuid, groupName)
        }
        val offlineMap = offlinePreviewByProfile[profile.uuid]
        val row = offlineMap?.let { uniqueGroupMatch(it, groupName) }
        val now = offlineSelectedForGroup(profile.uuid, groupName)
        val type = row?.type ?: Proxy.Type.Selector
        // Engine cache may not yet hold a sibling group the user just tapped (the prior
        // fetch only walked the tree rooted at the previously visible group). Render the
        // subscription's member names as a placeholder so the row is usable immediately;
        // live engine data overwrites it on the next setProxyDetails tick.
        val placeholderMembers = row?.members.orEmpty().map { n ->
            Proxy(n, n, "", offlineProxyType(profile.uuid, n), -1)
        }
        return ProxyGroup(
            type,
            placeholderMembers,
            now,
        ).withSelectionOverlay(this, profile.uuid, groupName)
    }
    val offline = offlinePreviewByProfile[profile.uuid] ?: return null
    val row = uniqueGroupMatch(offline, groupName) ?: return null
    val names = row.members
    val now = offlineSelectedForGroup(profile.uuid, groupName)
    return ProxyGroup(
        row.type,
        names.map { n ->
            Proxy(n, n, "", offlineProxyType(profile.uuid, n), -1)
        },
        now,
    ).withSelectionOverlay(this, profile.uuid, groupName)
}

internal fun ProfileAdapter.hasLiveProxyDetail(profile: Profile, groupName: String): Boolean {
    if (!useEngineFor(profile)) return false
    if (proxyDetails.containsKey(groupName)) return true
    return proxyDetails.keys.any { groupsMatchKey(groupName, it) }
}

internal fun ProfileAdapter.resolvePreferredGroupFromList(profile: Profile, groupNames: List<String>): String {
    if (groupNames.isEmpty()) return ""
    lastGroupHint?.let { hint ->
        groupNames.firstOrNull { groupsMatchKey(it, hint) }?.let { return it }
    }
    offlineSelectionsByProfile[profile.uuid]?.forEach { (g, sel) ->
        if (sel.isBlank()) return@forEach
        groupNames.firstOrNull { groupsMatchKey(it, g) }?.let { return it }
    }
    val prefix = "${profile.uuid}|"
    for (key in pendingProxySelections.keys) {
        if (!key.startsWith(prefix)) continue
        val g = key.substring(prefix.length)
        if (g.isBlank()) continue
        groupNames.firstOrNull { groupsMatchKey(it, g) }?.let { return it }
    }
    return groupNames.first()
}

internal fun ProfileAdapter.offlineSelectedForGroup(uuid: UUID, engineGroupName: String): String {
    val m = offlineSelectionsByProfile[uuid] ?: return ""
    m[engineGroupName]?.takeIf { it.isNotBlank() }?.let { return it }
    return m.entries.firstOrNull { (g, _) -> groupsMatchKey(engineGroupName, g) }
        ?.value.orEmpty()
}

internal fun ProfileAdapter.pendingMapValueForGroup(uuid: UUID, engineGroupName: String): String? {
    val exact = proxySelectionKey(uuid, engineGroupName)
    pendingProxySelections[exact]?.takeIf { it.isNotBlank() }?.let { return it }
    val prefix = "${uuid}|"
    for ((k, v) in pendingProxySelections) {
        if (!k.startsWith(prefix)) continue
        if (v.isBlank()) continue
        val g = k.substring(prefix.length)
        if (groupsMatchKey(engineGroupName, g)) return v
    }
    return null
}

internal fun ProfileAdapter.desiredUiSelection(uuid: UUID, engineGroupName: String): String? {
    pendingMapValueForGroup(uuid, engineGroupName)?.let { return it }
    offlineSelectedForGroup(uuid, engineGroupName).takeIf { it.isNotBlank() }?.let { return it }
    return null
}

internal fun ProxyGroup.withSelectionOverlay(adapter: ProfileAdapter, uuid: UUID, engineGroupName: String): ProxyGroup {
    val target = adapter.desiredUiSelection(uuid, engineGroupName) ?: return this
    if (target == now) return this
    if (proxies.isNotEmpty() && proxies.none { it.name == target }) return this
    return copy(now = target)
}

/** Current choice of [groupName] as the accordion summary shows it (pending pick wins). */
internal fun ProfileAdapter.groupSelectionSummary(profile: Profile, groupName: String): String? {
    val pg = proxyGroupForRow(profile, groupName)
    val pendingChoice = pendingMapValueForGroup(profile.uuid, groupName)?.takeIf { it.isNotBlank() }
    val selectedName = pendingChoice ?: pg?.now
    return selectedName?.takeIf { it.isNotBlank() }?.let { name ->
        pg?.proxies?.firstOrNull { it.name == name }?.let { it.title.ifBlank { it.name } } ?: name
    }
}

/**
 * Empty-state hint for the bottom-sheet node list: distinguishes "this group has no nodes yet"
 * (loading / connect the VPN) from "the current filter matched nothing".
 */
internal fun ProfileAdapter.proxyPickerEmptyHint(profile: Profile, groupName: String?, groupHasNodes: Boolean): Int =
    when {
        groupHasNodes -> R.string.profile_proxy_empty_filtered
        useEngineFor(profile) && !hasLiveProxyDetail(profile, groupName.orEmpty()) -> R.string.proxy_nodes_loading
        useEngineFor(profile) -> R.string.proxy_group_empty_runtime
        else -> R.string.proxy_nodes_empty_connect_vpn
    }

internal fun ProfileAdapter.buildProxyPickerRows(
    profile: Profile,
    groupNames: List<String>,
    currentGroupIndex: Int,
): List<ProxyPickerRow> {
    val selectedIndex = currentGroupIndex.coerceIn(0, groupNames.lastIndex)
    return groupNames.flatMapIndexed { groupIndex, groupName ->
        val pg = proxyGroupForRow(profile, groupName) ?: return@flatMapIndexed emptyList()
        val pendingChoice = pendingMapValueForGroup(profile.uuid, groupName)
            ?.takeIf { it.isNotBlank() }
        val effectiveNow = pendingChoice ?: pg.now
        pg.proxies.mapIndexedNotNull { proxyIndex, proxy ->
            if (shouldHideProxyOption(groupName, proxy)) return@mapIndexedNotNull null
            ProxyPickerRow(
                groupName = groupName,
                groupIndex = groupIndex,
                proxy = proxy,
                configIndex = groupIndex * 100_000 + proxyIndex,
                delayMs = resolveProxyDelay(profile.uuid, proxy),
                selected = proxy.name.isNotEmpty() && proxy.name == effectiveNow,
                provider = providerNameForProxy(proxy.name),
            )
        }
    }.let { rows ->
        val selectedGroupRows = rows.filter { it.groupIndex == selectedIndex }
        rows.filterNot { it.groupIndex == selectedIndex } + selectedGroupRows
    }.sortedBy { it.configIndex }
}

internal fun ProfileAdapter.resolveProxyDelay(uuid: UUID, proxy: Proxy): Int {
    val key = "${uuid}|${proxy.name}"
    val standalone = standalonePingDelays[key]
    val nested = if (serverPingMode == ServerPingMode.ThroughServer) nestedGroupDelay(proxy.name) else -1
    return serverPingMode.resolveDelay(standalone, proxy.delay, nested)
}

internal fun ProfileAdapter.nestedGroupDelay(proxyName: String): Int {
    val seen = linkedSetOf<String>()

    fun resolve(groupName: String): Int {
        if (!seen.add(groupName)) return -1

        proxyDetails[groupName]?.let { group ->
            val selectedDelay = group.proxies
                .firstOrNull { it.name == group.now && it.delay >= 0 }
                ?.delay
            if (selectedDelay != null) return selectedDelay

            return group.proxies.asSequence()
                .map { proxy ->
                    when {
                        proxy.delay >= 0 -> proxy.delay
                        proxy.name in proxyDetails -> resolve(proxy.name)
                        else -> -1
                    }
                }
                .filter { it >= 0 }
                .minOrNull() ?: -1
        }

        return -1
    }

    return resolve(proxyName)
}

internal fun ProfileAdapter.resolveCurrentNodeDisplayName(profile: Profile): String? {
    val groups = groupsForSelectionSummary(profile)
    if (groups.isEmpty()) return null

    val preferred = selectedGroupForSummary(profile)
    val orderedGroups = buildList {
        preferred?.let { add(it) }
        addAll(groups.filterNot { it == preferred })
    }

    for (group in orderedGroups) {
        val selected = resolvedSelectedProxyName(profile, group)
            ?.let(::displayGroupName)
        if (!selected.isNullOrBlank()) return selected
    }

    val offlineSelected = offlineSelectionsByProfile[profile.uuid]
        ?.values
        ?.firstOrNull { it.isNotBlank() }
        ?.let(::displayGroupName)
    if (!offlineSelected.isNullOrBlank()) return offlineSelected

    return null
}

internal fun ProfileAdapter.resolvedSelectedProxyName(profile: Profile, groupName: String): String? =
    resolvedSelectedProxyName(profile, groupName, linkedSetOf())

internal fun ProfileAdapter.resolvedSelectedProxyName(
    profile: Profile,
    groupName: String,
    seen: MutableSet<String>,
): String? {
    if (!seen.add(groupName)) return null

    val selected = proxyGroupForRow(profile, groupName)
        ?.now
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (shouldHideProxyName(groupName, selected)) return null

    if (selectedLooksLikeGroup(profile, groupName, selected)) {
        resolvedSelectedProxyName(profile, selected, seen)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    return selected
}

internal fun ProfileAdapter.selectedLooksLikeGroup(profile: Profile, groupName: String, selected: String): Boolean {
    val selectedProxy = proxyGroupForRow(profile, groupName)
        ?.proxies
        ?.firstOrNull { it.name == selected }
    return if (useEngineFor(profile)) {
        selectedProxy?.type?.group == true || selected in proxyDetails
    } else {
        selected in offlinePreviewByProfile[profile.uuid].orEmpty()
    }
}
