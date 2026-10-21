package com.github.kr328.clash.service.util

/**
 * Hides XDrive (core patch 0003) storage credentials from read-only views and exports:
 * the OAuth client ID / secret / refresh token in `xdrive-opts.secrets`, static tokens in
 * `xdrive-opts.template.auth`, and in the template operations (`put` / `get` / `list` /
 * `delete`) the values of credential-looking headers and URL query parameters, and any request
 * body that names a credential-looking field. They grant access to the operator's storage account rather
 * than to a single node, and no display-only consumer needs them. Raw node extraction stays separate for Mikan
 * offline connection tests; editors keep their original configuration values.
 */
object XDriveSecretRedactor {
    const val REDACTED = "[redacted]"

    private const val OPTS = "xdrive-opts"

    /** Returns [proxy] with its XDrive credentials replaced, or [proxy] itself if it has none. */
    fun redactProxy(proxy: Map<*, *>): Map<*, *> {
        // Keys are matched as the engine decodes them (`XDrive_Opts`, `Secrets`, ...).
        var out: LinkedHashMap<String, Any?>? = null
        for ((key, value) in proxy) {
            if (!MihomoKeys.matches(key, OPTS)) continue
            val opts = value as? Map<*, *> ?: continue
            val redacted = redactOpts(opts) ?: continue
            out = (out ?: copy(proxy)).also { it[key.toString()] = redacted }
        }
        return out ?: proxy
    }

    fun redactEntryYaml(text: String): String {
        if (!text.contains("xdrive", ignoreCase = true)) return text
        val document = MihomoConfigDocument.parse(text) ?: return REDACTED
        val redacted = redactProxy(document.root)
        return if (redacted === document.root) text else YamlFormatting.blockYaml().dump(redacted).trimEnd()
    }

    private fun redactOpts(opts: Map<*, *>): Map<String, Any?>? {
        val redacted = copy(opts)
        var changed = false
        for ((key, value) in opts) {
            when {
                MihomoKeys.matches(key, "secrets") && value is List<*> -> {
                    redacted[key.toString()] = value.map { REDACTED }
                    changed = true
                }
                MihomoKeys.matches(key, "template") && value is Map<*, *> -> {
                    val t = copy(value)
                    var templateChanged = false
                    for ((tk, tv) in value) {
                        if (MihomoKeys.matches(tk, "auth")) {
                            t[tk.toString()] = redactLeaves(tv)
                            templateChanged = true
                        } else if (TEMPLATE_OPS.any { MihomoKeys.matches(tk, it) } && tv is Map<*, *>) {
                            redactOp(tv)?.let {
                                t[tk.toString()] = it
                                templateChanged = true
                            }
                        }
                    }
                    if (templateChanged) {
                        redacted[key.toString()] = t
                        changed = true
                    }
                }
            }
        }
        return redacted.takeIf { changed }
    }

    /**
     * Redacts every XDrive proxy in a full config (`proxies:` and inline `proxy-providers`
     * payloads). Text that never mentions `xdrive` (any case) is returned untouched, byte for byte; otherwise
     * only the affected top-level blocks are re-rendered.
     */
    fun redactConfigYaml(text: String): String {
        if (!text.contains("xdrive", ignoreCase = true)) return text
        val document = MihomoConfigDocument.parse(text) ?: return REDACTED
        val root = document.root
        val replaced = mutableListOf<String>()

        (root["proxies"] as? List<*>)?.let { proxies ->
            val out = redactList(proxies)
            if (out != null) {
                root["proxies"] = out
                replaced += "proxies"
            }
        }
        (root["listeners"] as? List<*>)?.let { listeners ->
            redactList(listeners)?.let {
                root["listeners"] = it
                replaced += "listeners"
            }
        }
        (root["proxy-providers"] as? Map<*, *>)?.let { providers ->
            var changed = false
            val out = LinkedHashMap<String, Any?>(providers.size)
            for ((name, provider) in providers) {
                val map = provider as? Map<*, *>
                if (map == null) {
                    out[name.toString()] = provider
                    continue
                }
                // Every key the case-insensitive provider decoder could read as `payload`.
                var providerChanged = false
                val rebuilt = LinkedHashMap<String, Any?>(map.size)
                for ((k, v) in map) {
                    val redacted = if (MihomoKeys.matches(k, "payload") && v is List<*>) redactList(v) else null
                    if (redacted != null) providerChanged = true
                    rebuilt[k.toString()] = redacted ?: v
                }
                if (providerChanged) {
                    out[name.toString()] = rebuilt
                    changed = true
                } else {
                    out[name.toString()] = provider
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

    /** A template operation with credential headers, query values and bodies hidden, or null if it has none. */
    private fun redactOp(op: Map<*, *>): Map<String, Any?>? {
        val out = copy(op)
        var changed = false
        for ((key, value) in op) {
            when {
                MihomoKeys.matches(key, "headers") && value is Map<*, *> -> {
                    val headers = copy(value)
                    var headersChanged = false
                    for ((name, headerValue) in value) {
                        if (headerValue != null && isCredentialName(name.toString())) {
                            headers[name.toString()] = REDACTED
                            headersChanged = true
                        }
                    }
                    if (headersChanged) {
                        out[key.toString()] = headers
                        changed = true
                    }
                }
                MihomoKeys.matches(key, "url") && value is String -> {
                    val url = redactQuery(value)
                    if (url != value) {
                        out[key.toString()] = url
                        changed = true
                    }
                }
                MihomoKeys.matches(key, "body") && value is String && bodyHasCredential(value) -> {
                    out[key.toString()] = REDACTED
                    changed = true
                }
            }
        }
        return out.takeIf { changed }
    }

    /**
     * True if a request body names a credential-looking field: a form-encoded `name=value` pair,
     * a JSON `"name":` member, or any other `name:` / `name=`. The body is then hidden whole:
     * its format is free-form, so picking values out of it could miss one.
     */
    internal fun bodyHasCredential(body: String): Boolean =
        BODY_FIELD.findAll(body).any { match ->
            val raw = match.groupValues[1]
            val name = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            isCredentialName(name)
        }

    /** Replaces the values of credential-looking query parameters; everything else is kept as written. */
    internal fun redactQuery(url: String): String {
        val start = url.indexOf('?')
        if (start < 0) return url
        val fragment = url.indexOf('#', start).let { if (it < 0) url.length else it }
        val query = url.substring(start + 1, fragment)
        val redacted = query.split('&').joinToString("&") { param ->
            val eq = param.indexOf('=')
            if (eq <= 0) return@joinToString param
            val name = runCatching { java.net.URLDecoder.decode(param.substring(0, eq), "UTF-8") }
                .getOrDefault(param.substring(0, eq))
            if (isCredentialName(name)) param.substring(0, eq + 1) + REDACTED else param
        }
        return url.substring(0, start + 1) + redacted + url.substring(fragment)
    }

    /** Header or query names that carry credentials: auth headers, cookies, and `*token*` / `*secret*` / `*key*`. */
    internal fun isCredentialName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in CREDENTIAL_NAMES || CREDENTIAL_PARTS.any { it in n }
    }

    private val TEMPLATE_OPS = listOf("put", "get", "list", "delete")
    private val BODY_FIELD = Regex("""([A-Za-z0-9_.%-]+)"?\s*[:=]""")
    private val CREDENTIAL_NAMES = setOf("authorization", "proxy-authorization", "auth", "sig", "signature")
    private val CREDENTIAL_PARTS = listOf("token", "secret", "key", "cookie", "password", "passwd")

    private fun redactLeaves(value: Any?): Any? = when (value) {
        null -> null
        // Keep the auth scheme readable (e.g. `type: static`), hide every credential.
        is Map<*, *> -> LinkedHashMap<String, Any?>(value.size).also { out ->
            for ((k, v) in value) out[k.toString()] = if (MihomoKeys.matches(k, "type") && v is String) v else redactLeaves(v)
        }
        is List<*> -> value.map { redactLeaves(it) }
        else -> REDACTED
    }

    private fun copy(map: Map<*, *>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(map.size).also { out -> for ((k, v) in map) out[k.toString()] = v }
}
