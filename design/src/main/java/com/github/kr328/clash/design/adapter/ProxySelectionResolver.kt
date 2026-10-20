package com.github.kr328.clash.design.adapter

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.model.Profile
import java.util.UUID

internal fun proxySelectionKey(uuid: UUID, groupName: String): String =
    "${uuid}|${groupName}"

/**
 * Matches an engine group name against a stored one, tolerating the whitespace differences that
 * appear when a name round-trips through YAML ("US  Auto" vs "US Auto").
 *
 * Exact equality is checked first so an exact name always wins over a normalized near-match:
 * mihomo treats two names differing only in whitespace runs as two distinct groups, and callers
 * resolve with `firstOrNull`, which would otherwise silently pick whichever came first in map
 * order. See [uniqueGroupMatch] for the lookups that must not guess at all.
 */
internal fun groupsMatchKey(engineName: String, storedName: String): Boolean {
    if (engineName == storedName) return true
    return displayGroupName(engineName) == displayGroupName(storedName)
}

/**
 * Resolves [name] against [candidates] the way [groupsMatchKey] does, but returns null when the
 * normalized form is ambiguous instead of picking an arbitrary winner. Showing no data beats
 * showing another group's members under this group's heading.
 */
internal fun <V> uniqueGroupMatch(candidates: Map<String, V>, name: String): V? {
    candidates[name]?.let { return it }
    val matches = candidates.entries.filter { groupsMatchKey(name, it.key) }
    return matches.singleOrNull()?.value
}

internal fun shouldHideProxyOption(groupName: String, proxy: Proxy): Boolean {
    if (!groupName.equals("GLOBAL", ignoreCase = true)) return false
    if (proxy.type == Proxy.Type.Direct || proxy.type == Proxy.Type.Reject) return true
    return proxy.name.equals("DIRECT", ignoreCase = true) ||
        proxy.name.equals("REJECT", ignoreCase = true)
}

internal fun shouldHideProxyName(groupName: String, proxyName: String): Boolean {
    if (!groupName.equals("GLOBAL", ignoreCase = true)) return false
    return proxyName.equals("DIRECT", ignoreCase = true) ||
        proxyName.equals("REJECT", ignoreCase = true)
}

/**
 * Owns [ProfileAdapter]'s proxy-selection logic: which groups a profile shows, which group the
 * summaries follow, which node is selected in a group (engine detail vs offline preview, with the
 * pending pick overlaid) and the delay a node row displays. Reads the adapter's state through
 * [adapter]; holds no state of its own.
 */
internal class ProxySelectionResolver(private val adapter: ProfileAdapter) {
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
    private fun isFilteredOutAsNotSelectable(type: Proxy.Type): Boolean =
        adapter.excludeNotSelectable && type != Proxy.Type.Selector && type != Proxy.Type.Fallback

    /** Engine data applies only when the expanded card is the active profile and VPN is on. */
    internal fun useEngineFor(profile: Profile): Boolean =
        adapter.clashRunning &&
            profile.active &&
            profile.uuid == adapter.activeProfileUuid &&
            adapter.proxyGroupNames.isNotEmpty()

    internal fun effectiveGroupsForProfile(profile: Profile): List<String> {
        if (profile.uuid !in adapter.expandedUuids || !profile.imported) {
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
        val offlinePreview = adapter.offlinePreviewByProfile[profile.uuid]
        val visible = if (useEngineFor(profile)) {
            adapter.proxyGroupNames
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

    private fun groupsForSelectionSummary(profile: Profile): List<String> {
        if (!profile.imported) return emptyList()
        return if (useEngineFor(profile)) {
            adapter.proxyGroupNames
        } else {
            adapter.offlinePreviewByProfile[profile.uuid]?.keys?.toList().orEmpty()
        }
    }

    internal fun selectedGroupForSummary(profile: Profile): String? {
        val groups = groupsForSelectionSummary(profile)
        if (groups.isEmpty()) return null
        val uuid = profile.uuid
        primaryGroupIn(profile, groups)?.let { return it }
        val kept = adapter.selectedGroupIndex[uuid]?.takeIf { it in groups.indices }?.let { groups[it] }
        if (kept != null) {
            return kept
        }
        val picked = resolvePreferredGroupFromList(profile, groups)
        val index = groups.indexOf(picked).takeIf { it >= 0 } ?: 0
        adapter.selectedGroupIndex[uuid] = index.coerceIn(0, groups.lastIndex)
        return groups[adapter.selectedGroupIndex[uuid]!!]
    }

    /** The operator's primary group as named in [groups], when it applies to [profile]. */
    internal fun primaryGroupIn(profile: Profile, groups: List<String>): String? {
        val primary = adapter.primaryProxyGroup ?: return null
        if (profile.uuid != adapter.activeProfileUuid || adapter.tunnelMode == TunnelState.Mode.Global) return null
        return groups.firstOrNull { groupsMatchKey(it, primary) }
    }

    /**
     * Resolve a leaf node's protocol type **offline** from the parsed transport info
     * (config-overlay: config.yaml is the composed effective config). Without this the
     * picker shows no protocol/transport badge unless the engine is running — the type
     * was hard-coded to Unknown for offline placeholders. Empty/absent → Unknown.
     */
    private fun offlineProxyType(uuid: UUID, name: String): Proxy.Type {
        val leaf = proxyTypeFromYamlName(adapter.transportInfoByProfile[uuid]?.get(name)?.type)
        if (leaf != Proxy.Type.Unknown) return leaf
        // Not a leaf proxy → it may be a nested auto-group; surface its group type
        // (url-test / fallback / …) so the picker can chip it distinctly offline too.
        return adapter.offlinePreviewByProfile[uuid]?.get(name)?.type ?: Proxy.Type.Unknown
    }

    /**
     * YAML `type:` → [Proxy.Type]. The keys are the engine's **config** spellings from
     * `adapter/parser.go`, which are not the same as the wire names [Proxy.Type] is
     * modelled on (`gost-relay` here vs `GostRelay` from `AdapterType.String()`); the
     * short aliases (`ss`, `hy2`, `wg`, …) are ours, for hand-written configs.
     * In sync with mihomo v1.19.31 — re-check `parser.go` after every core bump.
     */
    private fun proxyTypeFromYamlName(raw: String?): Proxy.Type = when (raw?.trim()?.lowercase()) {
        "ss", "shadowsocks" -> Proxy.Type.Shadowsocks
        "ssr", "shadowsocksr" -> Proxy.Type.ShadowsocksR
        "snell" -> Proxy.Type.Snell
        "socks5", "socks" -> Proxy.Type.Socks5
        "http", "https" -> Proxy.Type.Http
        "vmess" -> Proxy.Type.Vmess
        "vless" -> Proxy.Type.Vless
        "trojan" -> Proxy.Type.Trojan
        "hysteria" -> Proxy.Type.Hysteria
        "hysteria2", "hy2" -> Proxy.Type.Hysteria2
        "tuic" -> Proxy.Type.Tuic
        "shadowquic" -> Proxy.Type.ShadowQuic
        "wireguard", "wg" -> Proxy.Type.WireGuard
        "ssh" -> Proxy.Type.Ssh
        "mieru" -> Proxy.Type.Mieru
        "anytls" -> Proxy.Type.AnyTLS
        "sudoku" -> Proxy.Type.Sudoku
        "masque" -> Proxy.Type.Masque
        "trusttunnel" -> Proxy.Type.TrustTunnel
        "openvpn" -> Proxy.Type.OpenVPN
        "tailscale" -> Proxy.Type.Tailscale
        "zerotier" -> Proxy.Type.ZeroTier
        "easytier" -> Proxy.Type.EasyTier
        "gost-relay" -> Proxy.Type.GostRelay
        "direct" -> Proxy.Type.Direct
        "reject" -> Proxy.Type.Reject
        "dns" -> Proxy.Type.Dns
        "rematch" -> Proxy.Type.Rematch
        else -> Proxy.Type.Unknown
    }

    internal fun proxyGroupForRow(profile: Profile, groupName: String): ProxyGroup? {
        if (useEngineFor(profile)) {
            val live = uniqueGroupMatch(adapter.proxyDetails, groupName)
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
                return live.withSelectionOverlay(profile.uuid, groupName)
            }
            val offlineMap = adapter.offlinePreviewByProfile[profile.uuid]
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
            ).withSelectionOverlay(profile.uuid, groupName)
        }
        val offline = adapter.offlinePreviewByProfile[profile.uuid] ?: return null
        val row = uniqueGroupMatch(offline, groupName) ?: return null
        val names = row.members
        val now = offlineSelectedForGroup(profile.uuid, groupName)
        return ProxyGroup(
            row.type,
            names.map { n ->
                Proxy(n, n, "", offlineProxyType(profile.uuid, n), -1)
            },
            now,
        ).withSelectionOverlay(profile.uuid, groupName)
    }

    internal fun hasLiveProxyDetail(profile: Profile, groupName: String): Boolean {
        if (!useEngineFor(profile)) return false
        if (adapter.proxyDetails.containsKey(groupName)) return true
        return adapter.proxyDetails.keys.any { groupsMatchKey(groupName, it) }
    }

    internal fun resolvePreferredGroupFromList(profile: Profile, groupNames: List<String>): String {
        if (groupNames.isEmpty()) return ""
        adapter.lastGroupHint?.let { hint ->
            groupNames.firstOrNull { groupsMatchKey(it, hint) }?.let { return it }
        }
        adapter.offlineSelectionsByProfile[profile.uuid]?.forEach { (g, sel) ->
            if (sel.isBlank()) return@forEach
            groupNames.firstOrNull { groupsMatchKey(it, g) }?.let { return it }
        }
        val prefix = "${profile.uuid}|"
        for (key in adapter.pendingProxySelections.keys) {
            if (!key.startsWith(prefix)) continue
            val g = key.substring(prefix.length)
            if (g.isBlank()) continue
            groupNames.firstOrNull { groupsMatchKey(it, g) }?.let { return it }
        }
        return groupNames.first()
    }

    internal fun offlineSelectedForGroup(uuid: UUID, engineGroupName: String): String {
        val m = adapter.offlineSelectionsByProfile[uuid] ?: return ""
        m[engineGroupName]?.takeIf { it.isNotBlank() }?.let { return it }
        return m.entries.firstOrNull { (g, _) -> groupsMatchKey(engineGroupName, g) }
            ?.value.orEmpty()
    }

    internal fun pendingMapValueForGroup(uuid: UUID, engineGroupName: String): String? {
        val exact = proxySelectionKey(uuid, engineGroupName)
        adapter.pendingProxySelections[exact]?.takeIf { it.isNotBlank() }?.let { return it }
        val prefix = "${uuid}|"
        for ((k, v) in adapter.pendingProxySelections) {
            if (!k.startsWith(prefix)) continue
            if (v.isBlank()) continue
            val g = k.substring(prefix.length)
            if (groupsMatchKey(engineGroupName, g)) return v
        }
        return null
    }

    private fun desiredUiSelection(uuid: UUID, engineGroupName: String): String? {
        pendingMapValueForGroup(uuid, engineGroupName)?.let { return it }
        offlineSelectedForGroup(uuid, engineGroupName).takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun ProxyGroup.withSelectionOverlay(uuid: UUID, engineGroupName: String): ProxyGroup {
        val target = desiredUiSelection(uuid, engineGroupName) ?: return this
        if (target == now) return this
        if (proxies.isNotEmpty() && proxies.none { it.name == target }) return this
        return copy(now = target)
    }

    /** Current choice of [groupName] as the accordion summary shows it (pending pick wins). */
    internal fun groupSelectionSummary(profile: Profile, groupName: String): String? {
        val pg = proxyGroupForRow(profile, groupName)
        val pendingChoice = pendingMapValueForGroup(profile.uuid, groupName)?.takeIf { it.isNotBlank() }
        // A group whose members were never loaded only has the on-disk pick (or nothing, for
        // url-test groups); the engine's batch answer is authoritative until its detail arrives.
        val engineChoice = adapter.groupSelections[groupName]
            ?.takeIf { it.isNotBlank() && useEngineFor(profile) && !hasLiveProxyDetail(profile, groupName) }
        val selectedName = pendingChoice ?: engineChoice ?: pg?.now
        return selectedName?.takeIf { it.isNotBlank() }?.let { name ->
            pg?.proxies?.firstOrNull { it.name == name }?.let { it.title.ifBlank { it.name } } ?: name
        }
    }

    internal fun resolveProxyDelay(uuid: UUID, proxy: Proxy): Int {
        val key = "${uuid}|${proxy.name}"
        val standalone = adapter.standalonePingDelays[key]
        val nested = nestedGroupDelay(uuid, proxy.name)
        return when {
            proxy.delay >= 0 -> proxy.delay
            nested >= 0 -> nested
            standalone != null -> standalone
            else -> proxy.delay
        }
    }

    private fun nestedGroupDelay(uuid: UUID, proxyName: String): Int {
        val seen = linkedSetOf<String>()

        fun resolve(groupName: String): Int {
            if (!seen.add(groupName)) return -1

            adapter.proxyDetails[groupName]?.let { group ->
                val selectedDelay = group.proxies
                    .firstOrNull { it.name == group.now && it.delay >= 0 }
                    ?.delay
                if (selectedDelay != null) return selectedDelay

                return group.proxies.asSequence()
                    .map { proxy ->
                        when {
                            proxy.delay >= 0 -> proxy.delay
                            proxy.name in adapter.proxyDetails -> resolve(proxy.name)
                            else -> adapter.standalonePingDelays["$uuid|${proxy.name}"] ?: -1
                        }
                    }
                    .filter { it >= 0 }
                    .minOrNull() ?: -1
            }

            val offline = adapter.offlinePreviewByProfile[uuid]?.get(groupName) ?: return -1
            return offline.members.asSequence()
                .map { name ->
                    val direct = adapter.standalonePingDelays["$uuid|$name"]
                    direct ?: resolve(name)
                }
                .filter { it >= 0 }
                .minOrNull() ?: -1
        }

        return resolve(proxyName)
    }

    internal fun resolveCurrentNodeDisplayName(profile: Profile): String? {
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

        val offlineSelected = adapter.offlineSelectionsByProfile[profile.uuid]
            ?.values
            ?.firstOrNull { it.isNotBlank() }
            ?.let(::displayGroupName)
        if (!offlineSelected.isNullOrBlank()) return offlineSelected

        return null
    }

    internal fun resolvedSelectedProxyName(profile: Profile, groupName: String): String? =
        resolvedSelectedProxyName(profile, groupName, linkedSetOf())

    private fun resolvedSelectedProxyName(
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

    private fun selectedLooksLikeGroup(profile: Profile, groupName: String, selected: String): Boolean {
        val selectedProxy = proxyGroupForRow(profile, groupName)
            ?.proxies
            ?.firstOrNull { it.name == selected }
        return if (useEngineFor(profile)) {
            selectedProxy?.type?.group == true || selected in adapter.proxyDetails
        } else {
            selected in adapter.offlinePreviewByProfile[profile.uuid].orEmpty()
        }
    }
}
