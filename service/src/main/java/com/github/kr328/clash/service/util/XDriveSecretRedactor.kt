package com.github.kr328.clash.service.util

/**
 * Hides XDrive (core patch 0003) storage credentials from read-only views and exports:
 * the OAuth client ID / secret / refresh token in `xdrive-opts.secrets` and static tokens in
 * `xdrive-opts.template.auth`. They grant access to the operator's storage account rather
 * than to a single node, and no read-only consumer needs them (the offline ping reads only
 * server/port). Editors read the config through their own paths and keep the real values.
 */
object XDriveSecretRedactor {
    const val REDACTED = "[redacted]"

    private const val OPTS = "xdrive-opts"

    /** Returns [proxy] with its XDrive credentials replaced, or [proxy] itself if it has none. */
    fun redactProxy(proxy: Map<*, *>): Map<*, *> {
        val opts = proxy[OPTS] as? Map<*, *> ?: return proxy
        val redacted = copy(opts)
        var changed = false
        (opts["secrets"] as? List<*>)?.let { secrets ->
            redacted["secrets"] = secrets.map { REDACTED }
            changed = true
        }
        (opts["template"] as? Map<*, *>)?.let { template ->
            if (template.containsKey("auth")) {
                val t = copy(template)
                t["auth"] = redactLeaves(template["auth"])
                redacted["template"] = t
                changed = true
            }
        }
        if (!changed) return proxy
        return copy(proxy).also { it[OPTS] = redacted }
    }

    /**
     * Redacts every XDrive proxy in a full config (`proxies:` and inline `proxy-providers`
     * payloads). Text without `xdrive-opts` is returned untouched, byte for byte; otherwise
     * only the affected top-level blocks are re-rendered.
     */
    fun redactConfigYaml(text: String): String {
        if (!text.contains(OPTS)) return text
        val document = MihomoConfigDocument.parse(text) ?: return text
        val root = document.root
        val replaced = mutableListOf<String>()

        (root["proxies"] as? List<*>)?.let { proxies ->
            val out = redactList(proxies)
            if (out != null) {
                root["proxies"] = out
                replaced += "proxies"
            }
        }
        (root["proxy-providers"] as? Map<*, *>)?.let { providers ->
            var changed = false
            val out = LinkedHashMap<String, Any?>(providers.size)
            for ((name, provider) in providers) {
                val map = provider as? Map<*, *>
                val payload = (map?.get("payload") as? List<*>)?.let { redactList(it) }
                if (map == null || payload == null) {
                    out[name.toString()] = provider
                } else {
                    out[name.toString()] = copy(map).also { it["payload"] = payload }
                    changed = true
                }
            }
            if (changed) {
                root["proxy-providers"] = out
                replaced += "proxy-providers"
            }
        }
        if (replaced.isEmpty()) return text
        return document.renderReplacing(*replaced.toTypedArray())
    }

    private fun redactList(proxies: List<*>): List<Any?>? {
        var changed = false
        val out = proxies.map { entry ->
            val map = entry as? Map<*, *> ?: return@map entry
            val redacted = redactProxy(map)
            if (redacted !== map) changed = true
            redacted
        }
        return out.takeIf { changed }
    }

    private fun redactLeaves(value: Any?): Any? = when (value) {
        null -> null
        // Keep the auth scheme readable (e.g. `type: static`), hide every credential.
        is Map<*, *> -> LinkedHashMap<String, Any?>(value.size).also { out ->
            for ((k, v) in value) out[k.toString()] = if (k == "type" && v is String) v else redactLeaves(v)
        }
        is List<*> -> value.map { redactLeaves(it) }
        else -> REDACTED
    }

    private fun copy(map: Map<*, *>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(map.size).also { out -> for ((k, v) in map) out[k.toString()] = v }
}
