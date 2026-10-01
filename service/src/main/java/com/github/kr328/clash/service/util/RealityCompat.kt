package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.log.Log
import java.io.File

/**
 * Keeps REALITY nodes connectable to current Xray servers.
 *
 * Xray-core 26.9.8+ rejects a REALITY ClientHello that does not carry an
 * X25519MLKEM768 key share before X25519. mihomo strips ML-KEM from the hello by
 * default (`reality-opts.support-x25519mlkem768` is false) and, in utls 1.8.8,
 * only the `chrome` fingerprint (Chrome 133) carries the share at all — firefox
 * resolves to 120, safari to 16, and so on. So for every inline proxy that uses
 * REALITY we turn the flag on and pin `client-fingerprint: chrome` unless it is
 * already an ML-KEM-capable fingerprint. A REALITY node with no fingerprint at
 * all cannot dial in mihomo ("REALITY is based on uTLS"), so filling it in is a
 * fix on its own.
 *
 * Inline `proxies:` only: nodes inside proxy-providers come from remote files
 * the engine fetches itself, and mihomo's provider `override:` has no knob for
 * these two fields. Not tied to [ProxyHardeningMode]: this is compatibility,
 * not a security restriction.
 */
object RealityCompat {
    private val realityTypes = setOf("vless", "vmess", "trojan")

    /** `client-fingerprint` values whose ClientHello carries X25519MLKEM768 (metacubex/utls 1.8.8). */
    private val mlkemFingerprints = setOf("chrome")

    private const val PREFERRED_FINGERPRINT = "chrome"

    /** Rewrites `profileDir/config.yaml` in place; true when something changed. */
    fun applyToProfile(profileDir: File): Boolean {
        val configFile = File(profileDir, "config.yaml")
        if (!configFile.isFile) return false
        return try {
            val text = configFile.readText()
            val out = applyToText(text) ?: return false
            if (out == text) return false
            configFile.writeText(out)
            Log.i("RealityCompat: enabled ML-KEM / chrome fingerprint on REALITY proxies in ${configFile.name}")
            true
        } catch (e: Exception) {
            Log.w("RealityCompat: failed on ${configFile.absolutePath}: ${e.message}", e)
            false
        }
    }

    /**
     * Returns [text] with the REALITY proxies adjusted, [text] itself when nothing
     * needed changing, or null when the input is not a YAML map. Only the
     * `proxies:` block is re-rendered; every other block keeps its source text.
     */
    fun applyToText(text: String): String? {
        val document = MihomoConfigDocument.parse(text) ?: return null
        return if (apply(document.root)) document.renderReplacing("proxies") else text
    }

    /** Mutates [root] in place; true when at least one proxy changed. */
    fun apply(root: MutableMap<String, Any?>): Boolean {
        val proxies = root["proxies"] as? MutableList<Any?> ?: return false
        var changed = false
        for (i in proxies.indices) {
            val proxy = proxies[i] as? Map<*, *> ?: continue
            if (!isRealityProxy(proxy)) continue
            val mutable = LinkedHashMap<String, Any?>(proxy.size + 1)
            for ((k, v) in proxy) mutable[k?.toString() ?: continue] = v
            if (adjust(mutable)) {
                proxies[i] = mutable
                changed = true
            }
        }
        return changed
    }

    private fun isRealityProxy(proxy: Map<*, *>): Boolean {
        val type = proxy["type"]?.toString()?.trim()?.lowercase() ?: return false
        if (type !in realityTypes) return false
        val opts = proxy["reality-opts"] as? Map<*, *> ?: return false
        return !opts["public-key"]?.toString().isNullOrBlank()
    }

    private fun adjust(proxy: MutableMap<String, Any?>): Boolean {
        var changed = false
        val opts = LinkedHashMap<String, Any?>()
        (proxy["reality-opts"] as Map<*, *>).forEach { (k, v) -> opts[k.toString()] = v }
        if (opts["support-x25519mlkem768"] != true) {
            opts["support-x25519mlkem768"] = true
            proxy["reality-opts"] = opts
            changed = true
        }
        val fingerprint = proxy["client-fingerprint"]?.toString()?.trim()?.lowercase().orEmpty()
        if (fingerprint !in mlkemFingerprints) {
            proxy["client-fingerprint"] = PREFERRED_FINGERPRINT
            changed = true
        }
        return changed
    }
}
