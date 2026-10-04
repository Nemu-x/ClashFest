package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProfileSnapshot
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class ChainEndpoint(val profileId: String, val proxyName: String, val yaml: String)

@Serializable
data class SubscriptionChain(val id: String, val first: ChainEndpoint, val exit: ChainEndpoint) {
    val firstAlias: String get() = "Mikan · ${first.proxyName} · IN"
    val exitAlias: String get() = "Mikan · ${exit.proxyName} · OUT"
}

object SubscriptionChainComposer {
    fun hasChainAliases(config: String): Boolean {
        val entries = MihomoConfigDocument.parse(config)?.root?.get("proxies") as? List<*> ?: return false
        return entries.any {
            val node = it as? Map<*, *> ?: return@any false
            val name = node["name"] as? String ?: return@any false
            val dialer = node["dialer-proxy"] as? String ?: return@any false
            name.startsWith("Mikan · ") && name.endsWith(" · OUT") && dialer.startsWith("Mikan · ") && dialer.endsWith(" · IN")
        }
    }

    fun proxies(snapshot: ProfileSnapshot, profileDir: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val outputBudget = PreviewOutputBudget()
        fun collect(entries: List<*>, overrides: Map<*, *> = emptyMap<Any, Any>()) {
            for (raw in entries.take(PreviewResourceLimits.MAX_PROXY_ENTRIES_SCANNED)) {
                if (result.size >= PreviewResourceLimits.MAX_TRANSPORTS) break
                val node = (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap() ?: continue
                val original = node["name"] as? String ?: continue
                if (original.startsWith("Mikan · ") && (original.endsWith(" · IN") || original.endsWith(" · OUT"))) continue
                for ((key, value) in overrides) if (key != "additional-prefix" && key != "additional-suffix") node[key.toString()] = value
                val name = (overrides["additional-prefix"] as? String).orEmpty() + original + (overrides["additional-suffix"] as? String).orEmpty()
                if (name.isBlank() || name.length > PreviewResourceLimits.MAX_NAME_CHARS) continue
                node["name"] = name
                val yaml = YamlFormatting.blockYaml().dump(node)
                if (yaml.length <= PreviewResourceLimits.MAX_OUTPUT_CHARS / 4 && outputBudget.accept(name)) result[name] = yaml
            }
        }
        collect(snapshot.proxies.map(JsonElementToYaml::convertObject))
        val budget = ProviderFileReadBudget()
        for ((_, json) in snapshot.proxyProviders.entries.take(PreviewResourceLimits.MAX_PROVIDER_FILES)) {
            val provider = JsonElementToYaml.convertObject(json)
            val overrides = provider["override"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val payload = provider["payload"] as? List<*>
            if (payload != null) { collect(payload, overrides); continue }
            val path = provider["path"] as? String
            val file = if (!path.isNullOrBlank()) ProxyDialerYamlEdit.resolveProviderPath(profileDir, path)
            else (provider["url"] as? String)?.takeIf { it.isNotBlank() }?.let {
                val md5 = MessageDigest.getInstance("MD5").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
                File(profileDir, "providers/proxies/$md5")
            }
            val text = file?.let(budget::readUtf8) ?: continue
            val root = runCatching { YamlFormatting.parseRootMap(text) }.getOrNull() ?: continue
            collect(root["proxies"] as? List<*> ?: continue, overrides)
        }
        return result
    }

    fun refresh(chain: SubscriptionChain, importedDir: File, parse: (File) -> ProfileSnapshot): SubscriptionChain {
        val nodes = mutableMapOf<String, Map<String, String>>()
        fun resolve(endpoint: ChainEndpoint): ChainEndpoint {
            val uuid = UUID.fromString(endpoint.profileId)
            require(uuid.toString() == endpoint.profileId)
            val dir = File(importedDir, uuid.toString())
            val config = File(dir, "config.yaml")
            require(config.isFile && config.length() <= PreviewResourceLimits.MAX_CONFIG_BYTES) { "Chain subscription is unavailable" }
            val yaml = nodes.getOrPut(endpoint.profileId) { proxies(parse(dir), dir) }[endpoint.proxyName]
            requireNotNull(yaml) { "Chain server is unavailable" }
            return endpoint.copy(yaml = yaml)
        }
        return chain.copy(first = resolve(chain.first), exit = resolve(chain.exit))
    }

    private fun node(endpoint: ChainEndpoint, name: String): MutableMap<String, Any?> {
        require(endpoint.yaml.length <= PreviewResourceLimits.MAX_OUTPUT_CHARS / 4)
        val raw = requireNotNull(YamlFormatting.parseRootMap(endpoint.yaml)).toMutableMap()
        require(raw["name"] == endpoint.proxyName)
        require((raw["type"] as? String)?.lowercase() !in listOf(null, "direct", "reject", "pass", "dns", "selector", "url-test"))
        require(!raw.containsKey("dialer-proxy")) { "A server already using another chain cannot be selected" }
        raw["name"] = name
        return raw
    }

    fun compose(config: String, chain: SubscriptionChain, previous: SubscriptionChain? = null): String {
        UUID.fromString(chain.id)
        require(chain.first.profileId != chain.exit.profileId || chain.first.proxyName != chain.exit.proxyName) { "Chain requires two different servers" }
        val clean = if (previous != null) remove(config, previous) else config
        val document = MihomoConfigDocument.parseOrThrow(clean)
        val proxies = (document.root["proxies"] as? List<*>).orEmpty()
        val names = proxies.mapNotNull { (it as? Map<*, *>)?.get("name") }
        require(chain.firstAlias !in names && chain.exitAlias !in names) { "Chain alias conflict" }
        val entry = node(chain.first, chain.firstAlias)
        val exit = node(chain.exit, chain.exitAlias).apply { put("dialer-proxy", chain.firstAlias) }
        document.root["proxies"] = proxies + listOf(entry, exit)
        val groups = (document.root["proxy-groups"] as? List<*>).orEmpty().filterNot { (it as? Map<*, *>)?.get("name") == "GLOBAL" }
        document.root["proxy-groups"] = groups + mapOf("name" to "GLOBAL", "type" to "select", "proxies" to listOf(chain.exitAlias))
        document.root["mode"] = "global"
        return document.renderReplacing("proxies", "proxy-groups", "mode")
    }

    fun remove(config: String, chain: SubscriptionChain): String {
        val document = MihomoConfigDocument.parseOrThrow(config)
        document.root["proxies"] = (document.root["proxies"] as? List<*>).orEmpty().filterNot {
            (it as? Map<*, *>)?.get("name") in listOf(chain.firstAlias, chain.exitAlias)
        }
        return document.renderReplacing("proxies")
    }
}
