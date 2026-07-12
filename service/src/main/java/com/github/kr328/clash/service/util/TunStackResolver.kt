package com.github.kr328.clash.service.util

/**
 * Resolves which TUN network stack to hand the VpnService fd (system / gvisor / mixed).
 *
 * Precedence (highest first):
 *  1. **Operator lock** — an `X-Network-Stack` subscription header of `system`/`gvisor`/`mixed`
 *     forces that stack, over the user's manual choice (operator policy, like the share-links lock).
 *     A header value of `auto` (or absent) means "don't lock" and falls through.
 *  2. **User setting** — an explicit app-setting choice of `system`/`gvisor`/`mixed`.
 *  3. **[AUTO] setting** — follow the *subscription*: the active profile's composed `config.yaml`
 *     declares `tun.stack` (mixed→mixed, system→system, gvisor→gvisor). Falls back to [DEFAULT] when
 *     the subscription doesn't declare a usable stack.
 *  4. **Default `system`** — matches upstream CMFA. Used when the app setting is absent (its default
 *     is `system`, not `auto`).
 */
object TunStackResolver {
    /** Sentinel meaning "no explicit lock": follow the subscription (app setting) / defer (header). */
    const val AUTO = "auto"

    /** Fallback stack when nothing forces a choice. Matches CMFA's default. */
    private const val DEFAULT = "system"

    /** Stacks mihomo's TUN listener understands. An unknown value is ignored rather than crashing. */
    private val KNOWN = setOf("gvisor", "system", "mixed", "lwip")

    /**
     * @param setting       the app-setting stack: an explicit stack, or [AUTO] to follow the subscription.
     * @param operatorStack the `X-Network-Stack` header value persisted for the active profile, or null.
     * @param configYaml    the composed `config.yaml` (subscription + user layer), or null — only read
     *                       when [setting] is [AUTO].
     */
    fun resolve(setting: String, operatorStack: String?, configYaml: String?): String {
        // 1. Operator lock wins outright.
        val operator = operatorStack?.trim()?.lowercase()
        if (operator != null && operator != AUTO && operator in KNOWN) return operator

        // 2. Explicit user override.
        val user = setting.trim().lowercase()
        if (user != AUTO && user in KNOWN) return user

        // 3. Auto → follow the subscription's declared tun.stack.
        if (user == AUTO) {
            val declared = runCatching {
                val root = configYaml?.let { MihomoConfigDocument.parse(it)?.root }
                (root?.get("tun") as? Map<*, *>)?.get("stack")?.toString()?.trim()?.lowercase()
            }.getOrNull()
            if (declared != null && declared in KNOWN) return declared
        }

        // 4. Default.
        return DEFAULT
    }
}
