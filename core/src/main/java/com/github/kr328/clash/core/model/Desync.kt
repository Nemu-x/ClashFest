package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Bridge shapes of the `type: desync` outbound (core patch 0021, see
 * docs/core-patches.md); the Go side is `cfa/native/desync`.
 */

/** What the desync library implements on this device. Static for a library version. */
@Serializable
data class DesyncCapabilities(
    val os: String,
    val fakeTcp: Boolean,
    val fakeUdp: Boolean,
    val ttlControl: Boolean,
    val disorder: Disorder,
    val oob: Boolean,
    val ack: Ack,
    val autoTtl: AutoTtl,
    /** Method ("fake-tcp", "fake-udp", "disorder", "oob", "auto-ttl-ipv4", ...) -> reason. */
    val unsupported: Map<String, String> = emptyMap(),
    val libraryVersion: String = "",
    val static: Boolean = true,
    val note: String = "",
) {
    @Serializable
    data class Disorder(val supported: Boolean, val mechanism: String, val observed: Boolean, val reliable: Boolean)

    @Serializable
    data class Ack(val mechanism: String, val sent: Boolean, val acked: Boolean, val reliable: Boolean)

    @Serializable
    data class AutoTtl(val mechanism: String, val ipv4: Boolean, val ipv6: Boolean)
}

/**
 * One strategy of the library's catalog. [options] are the keys of a `type: desync`
 * proxy (`tlsrec`, `split`, `fake`, `udp`, ...) as the YAML takes them.
 */
@Serializable
data class DesyncPreset(
    val id: String,
    val name: String,
    val description: String,
    val rank: Int,
    val options: JsonObject,
)

@Serializable
data class DesyncSkippedPreset(
    /** "tcp" or "udp". */
    val kind: String,
    val name: String,
    val reason: String,
)

/** The catalog filtered for this device, each list in the suggested order of trying. */
@Serializable
data class DesyncPresetCatalog(
    val catalogVersion: String,
    val defaultTcp: String,
    val tcp: List<DesyncPreset>,
    val udp: List<DesyncPreset>,
    val skipped: List<DesyncSkippedPreset> = emptyList(),
)

/** A service a desync-only profile can route; [domains] match with subdomains. */
@Serializable
data class DesyncService(
    val id: String,
    val name: String,
    val domains: List<String>,
)

@Serializable
data class DesyncConfigRequest(
    /** IDs of [com.github.kr328.clash.core.Clash.desyncServices]. */
    val services: List<String>,
    val customDomains: List<String> = emptyList(),
    /** A TCP preset ID; blank takes [DesyncPresetCatalog.defaultTcp]. */
    val strategyPresetId: String = "",
    /** Optional UDP preset ID added to every proxy. */
    val udpPresetId: String = "",
    /** Reject UDP/443 so apps fall back to TCP; null = on unless [udpPresetId] is set. */
    val rejectQuic: Boolean? = null,
)

/** Either the engine-validated [yaml] or the [error] that prevented it. */
@Serializable
data class DesyncGeneratedConfig(
    val yaml: String? = null,
    val error: String? = null,
)

/** Runtime state of one desync proxy of the loaded profile. */
@Serializable
data class DesyncProxyStatus(
    val name: String,
    /** Proxy provider name, empty for a proxy of `proxies:`. */
    val provider: String = "",
    val alive: Boolean = false,
    /**
     * The outbound's `desync` object: `strategy`, `tcp` (tlsrec, split, ..., `fake.last-ttl`),
     * `udp` (options and `counters`), `dns-fallback`, `reject-quic`.
     */
    val desync: JsonObject,
    val dnsFallbacks: Long? = null,
    val udpDiagnostics: JsonObject? = null,
)

@Serializable
data class DesyncStatus(
    val proxies: List<DesyncProxyStatus> = emptyList(),
)
