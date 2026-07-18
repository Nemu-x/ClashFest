package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

/**
 * A proxy-group with the membership **the engine itself computed**, produced by
 * `Bridge.nativeResolveProxyGroupsFromBytes`.
 *
 * Unlike [ProfileSnapshot], which exposes `proxy-groups` exactly as written in YAML (raw and
 * unresolved), this carries mihomo's answer: `include-all*` expanded, `use:` resolved,
 * `filter` / `exclude-filter` / `exclude-type` applied, provider name prefixes in place.
 *
 * Use this for the offline preview so the disconnected UI matches what the running tunnel
 * reports. Re-implementing mihomo's group semantics in Kotlin has repeatedly diverged from the
 * engine — a `filter` with a quantifier or lookahead silently collapsed a group to its declared
 * `proxies:`, and `use:`-backed groups resolved to nothing at all.
 */
@Serializable
data class ResolvedProxyGroup(
    val name: String,
    val type: String = "",
    /** Currently selected member, when the engine reports one. */
    val now: String = "",
    val hidden: Boolean = false,
    /** Resolved member names, in the engine's order. */
    val all: List<String> = emptyList(),
)
