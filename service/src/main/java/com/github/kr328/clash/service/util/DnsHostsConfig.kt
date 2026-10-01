package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProfileSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Editable model for a profile's name-resolution config (the `dns:` block and
 * the top-level `hosts:` map). Read from [ProfileSnapshot] (engine-parsed,
 * exactly as the user wrote it), serialized back into ordered YAML blocks for
 * the WRITE pipeline. Absent / empty fields are omitted so we never write
 * engine defaults or empty blocks.
 *
 * `enhancedMode` carries the raw mihomo value: `normal` (UI "Off"), `redir-host`,
 * or `fake-ip`.
 *
 * A `hosts` value is kept as one display string. mihomo accepts either a single
 * value or a list (`test.com: [1.1.1.1, 2.2.2.2]`, see `config.parseHosts` →
 * `utils.ToStringSlice`); a list is held here as `"1.1.1.1, 2.2.2.2"` and
 * written back as a YAML sequence by [toHostsBlock]. This keeps `user_layer.json`
 * and the editor's `host = value` lines unchanged for the single-value case.
 */
@Serializable
data class DnsHostsConfig(
    var enable: Boolean? = null,
    var ipv6: Boolean? = null,
    var enhancedMode: String? = null,
    var listen: String? = null,
    var cacheAlgorithm: String? = null,
    var nameserver: List<String> = emptyList(),
    var directNameserver: List<String> = emptyList(),
    var proxyServerNameserver: List<String> = emptyList(),
    var defaultNameserver: List<String> = emptyList(),
    var hosts: Map<String, String> = emptyMap(),
) {
    /** The `dns:` block value as an ordered map, or null when nothing is set. */
    fun toDnsBlock(): Map<String, Any?>? {
        val m = LinkedHashMap<String, Any?>()
        enable?.let { m["enable"] = it }
        ipv6?.let { m["ipv6"] = it }
        enhancedMode?.takeIf { it.isNotBlank() }?.let { m["enhanced-mode"] = it }
        DnsHostsValidator.normalizeListen(listen)?.let { m["listen"] = it }
        cacheAlgorithm?.takeIf { it.isNotBlank() }?.let { m["cache-algorithm"] = it }
        nameserver.cleaned().ifNotEmpty { m["nameserver"] = it }
        directNameserver.cleaned().ifNotEmpty { m["direct-nameserver"] = it }
        proxyServerNameserver.cleaned().ifNotEmpty { m["proxy-server-nameserver"] = it }
        defaultNameserver.cleaned().ifNotEmpty { m["default-nameserver"] = it }
        return m.takeIf { it.isNotEmpty() }
    }

    /**
     * The `hosts:` block value as an ordered map, or null when empty. A value
     * with several entries becomes a `List<String>` (YAML sequence), a single
     * entry stays a plain string.
     */
    fun toHostsBlock(): Map<String, Any>? {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in hosts) {
            val key = k.trim()
            val values = splitHostValues(v)
            if (key.isEmpty() || values.isEmpty()) continue
            out[key] = if (values.size == 1) values[0] else values
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** True when the model would write nothing (used by the master-toggle teardown). */
    fun isEmpty(): Boolean = toDnsBlock() == null && toHostsBlock() == null

    /**
     * Overlays the managed DNS fields onto an EXISTING `dns:` map, preserving
     * any keys this editor doesn't model (respect-rules, nameserver-policy,
     * fake-ip-filter, prefer-h3, use-hosts, …). A managed field that is
     * empty/cleared is removed from the map (so the user can drop it) without
     * touching unknown keys. This prevents Save from wiping the rest of a rich
     * provider `dns:` block.
     */
    fun mergeIntoDns(existing: MutableMap<String, Any?>) {
        fun set(key: String, value: Any?) {
            if (value != null) existing[key] = value else existing.remove(key)
        }
        set("enable", enable)
        set("ipv6", ipv6)
        set("enhanced-mode", enhancedMode?.takeIf { it.isNotBlank() })
        set("listen", DnsHostsValidator.normalizeListen(listen))
        set("cache-algorithm", cacheAlgorithm?.takeIf { it.isNotBlank() })
        set("nameserver", nameserver.cleaned().takeIf { it.isNotEmpty() })
        set("direct-nameserver", directNameserver.cleaned().takeIf { it.isNotEmpty() })
        set("proxy-server-nameserver", proxyServerNameserver.cleaned().takeIf { it.isNotEmpty() })
        set("default-nameserver", defaultNameserver.cleaned().takeIf { it.isNotEmpty() })
    }

    private fun List<String>.cleaned(): List<String> =
        map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun List<String>.ifNotEmpty(block: (List<String>) -> Unit) {
        if (isNotEmpty()) block(this)
    }

    companion object {
        fun fromSnapshot(snapshot: ProfileSnapshot): DnsHostsConfig =
            from(snapshot.dns, snapshot.hosts)

        fun from(dns: JsonObject?, hosts: JsonObject?): DnsHostsConfig {
            val c = DnsHostsConfig()
            if (dns != null) {
                c.enable = dns["enable"]?.jsonPrimitive?.booleanOrNull
                c.ipv6 = dns["ipv6"]?.jsonPrimitive?.booleanOrNull
                c.enhancedMode = dns.str("enhanced-mode")
                c.listen = dns.str("listen")
                c.cacheAlgorithm = dns.str("cache-algorithm")
                c.nameserver = dns.strList("nameserver")
                c.directNameserver = dns.strList("direct-nameserver")
                c.proxyServerNameserver = dns.strList("proxy-server-nameserver")
                c.defaultNameserver = dns.strList("default-nameserver")
            }
            if (hosts != null) {
                val map = LinkedHashMap<String, String>()
                for ((k, v) in hosts) {
                    // The engine snapshot hands a list host through as a JSON array;
                    // `.jsonPrimitive` on it throws, which used to crash the editor.
                    val value = when (v) {
                        is JsonArray -> joinHostValues(v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                        is JsonPrimitive -> v.contentOrNull
                        else -> null
                    }
                    if (value.isNullOrBlank()) continue
                    map[k] = value
                }
                c.hosts = map
            }
            return c
        }

        /**
         * Splits a display value into its entries: `"1.1.1.1, 2.2.2.2"`,
         * `"[1.1.1.1, 2.2.2.2]"` (pasted YAML flow form) or `"1.1.1.1 2.2.2.2"`.
         * A single value (an IP, `lan`, or a domain alias) comes back as one entry.
         */
        fun splitHostValues(value: String): List<String> =
            value.trim().removePrefix("[").removeSuffix("]")
                .split(',', ' ', '\t')
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() }

        fun joinHostValues(values: List<String>): String =
            values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        private fun JsonObject.strList(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    }
}

/** Pure validators used both by the UI (inline errors) and unit tests. */
object DnsHostsValidator {
    enum class Error {
        LISTEN_NOT_LOOPBACK,
        RESPECT_RULES_NEEDS_PROXY_NAMESERVER,
    }

    private val loopbackHosts = setOf("127.0.0.1", "::1", "localhost")

    private data class ListenAddress(val host: String, val port: String)

    /**
     * `dns.listen` must be empty or an explicit loopback host with a valid port.
     * The common `:port` shorthand is normalized to `127.0.0.1:port` before it
     * is persisted so it cannot become a wildcard bind.
     */
    fun listenError(listen: String?): Error? {
        val v = listen?.trim().orEmpty()
        if (v.isEmpty()) return null
        return if (normalizeListen(v) != null) null else Error.LISTEN_NOT_LOOPBACK
    }

    fun normalizeListen(listen: String?): String? {
        val raw = listen?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val address = parseListenAddress(raw) ?: return null
        return when {
            address.host.isEmpty() -> "127.0.0.1:${address.port}"
            normalizeHost(address.host) in loopbackHosts -> raw
            else -> null
        }
    }

    internal fun hardenListen(listen: String?): String? {
        val raw = listen?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val address = parseListenAddress(raw) ?: return null
        return if (normalizeHost(address.host) in loopbackHosts) raw else "127.0.0.1:${address.port}"
    }

    /** Engine: `respect-rules: true` requires a non-empty `proxy-server-nameserver`. */
    fun proxyServerNameserverError(respectRules: Boolean, proxyServerNameserver: List<String>): Error? {
        if (!respectRules) return null
        val any = proxyServerNameserver.any { it.trim().isNotEmpty() }
        return if (any) null else Error.RESPECT_RULES_NEEDS_PROXY_NAMESERVER
    }

    private fun parseListenAddress(raw: String): ListenAddress? {
        val host: String
        val port: String
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end <= 0 || end + 1 >= raw.length || raw[end + 1] != ':') return null
            host = raw.substring(0, end + 1)
            port = raw.substring(end + 2)
        } else {
            val colon = raw.lastIndexOf(':')
            if (colon < 0) return null
            host = raw.substring(0, colon)
            if (host.contains(':')) return null
            port = raw.substring(colon + 1)
        }
        val number = port.toIntOrNull() ?: return null
        if (number !in 0..65535) return null
        return ListenAddress(host, port)
    }

    private fun normalizeHost(host: String): String = host.trim().trim('[', ']').lowercase()
}
