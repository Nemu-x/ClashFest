package com.github.kr328.clash.common.util

import android.os.SystemClock
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Locale

/**
 * Latency tests that do not require Clash core / VPN — used when the engine is unavailable.
 */
object StandalonePing {
    const val TCP_UNSUPPORTED = -2

    /**
     * Reads `server` / `port` from a single proxy YAML block (Clash / Mihomo style).
     */
    fun parseServerPortFromProxyYaml(yaml: String): Pair<String, Int>? {
        val host = scalar(yaml, "server")?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
            ?: return null
        val port = if (Regex("(?m)^\\s*port:").containsMatchIn(yaml)) {
            scalar(yaml, "port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else 443
        return host to port
    }

    fun supportsTcpProbe(yaml: String): Boolean =
        scalar(yaml, "type")?.lowercase(Locale.ROOT) !in setOf("hysteria", "hysteria2", "tuic", "wireguard")

    private fun scalar(yaml: String, key: String): String? {
        val match = Regex("(?m)^[ \\t]*$key:[ \\t]*(?:\"([^\"]*)\"|'([^']*)'|([^#\\r\\n]*))")
            .find(yaml) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim()
    }

    /**
     * Built-in policy names — no remote host to TCP-probe from YAML.
     */
    fun isBuiltinProxyName(name: String): Boolean =
        name.uppercase(Locale.US) in BUILTIN_NAMES

    private val BUILTIN_NAMES = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")

    /**
     * Measures time to complete HTTPS/HTTP request handshake (GET; many CDNs block HEAD).
     */
    suspend fun measureHttpLatency(urlString: String): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clean = urlString.trim().substringBefore('#')
                require(clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
                    "not an http(s) url"
                }
                val url = URL(clean)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val t0 = SystemClock.elapsedRealtime()
                try {
                    conn.connect()
                    // Force the full request/response round-trip (not just the TCP connect) so the
                    // measured time reflects a real HTTP exchange. Return value intentionally ignored.
                    conn.responseCode
                    (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                } finally {
                    conn.disconnect()
                }
            }
        }

    /**
     * TCP connect to [host]:[port] (e.g. 443) — fallback when URL is not http(s).
     */
    suspend fun tcpConnectMs(host: String, port: Int, network: Network): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host.isNotBlank() && port in 1..65535)
                val address = network.getAllByName(host).firstOrNull()
                    ?: error("No server address")
                val t0 = SystemClock.elapsedRealtime()
                network.socketFactory.createSocket().use { s ->
                    s.connect(InetSocketAddress(address, port), 5_000)
                }
                (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            }
        }
}
