package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.RuleState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The user's in-app edits for a profile, stored as **intent** separate from the subscription
 * `config.yaml` (config-overlay-architecture, Group 2). This is ClashFest's equivalent of a
 * "merge profile": the subscription stays exactly as fetched, and this layer is
 * composed on top at apply time using only non-reconciling operations (Group 3).
 *
 * Each slot reuses an existing serializable edit model where one exists, so the editors can move
 * onto this store without inventing new shapes:
 *  - [rules]          rule editor state (also carries user rule-providers via [RuleState.providers])
 *  - [dnsHosts]       the user's `dns:` + `hosts:` override (whole-block replace)
 *  - [tunnels]        the user's `tunnels:` override (whole-block replace)
 *  - [proxyProviders] user-added proxy-providers as the inner-map YAML under `proxy-providers:`
 *                     (exactly what `ProxyProvidersYamlEdit.mergeIntoConfig` consumes; union)
 *  - [ruleProviders]  user-added rule-providers as the inner-map YAML under `rule-providers:`
 *                     (`RuleProvidersYamlEdit.mergeIntoConfig`; union)
 *  - [proxyChain]     proxy-chain (dialer-proxy) intent: target proxy name → dialer proxy name
 *  - [script]         the user's JS config script (see [UserScript])
 *
 * Population of the slots (re-pointing the editors) is Group 2.3; composition is Group 3. Until
 * then this type is inert.
 */
@Serializable
data class UserLayer(
    /** Schema version, for forward-compatible migration of the on-disk file. */
    val version: Int = CURRENT_VERSION,
    val rules: RuleState = RuleState(),
    val dnsHosts: DnsHostsConfig? = null,
    val tunnels: TunnelsConfig? = null,
    val proxyProviders: String? = null,
    val ruleProviders: String? = null,
    val relayGroups: List<RelayGroup> = emptyList(),
    val proxyChain: Map<String, String> = emptyMap(),
    val script: UserScript? = null,
) {
    /** True when the user has no edits — nothing to compose on top of the subscription. */
    fun isEmpty(): Boolean =
        rules.rules.isEmpty() &&
            rules.providers.isEmpty() &&
            dnsHosts == null &&
            tunnels == null &&
            proxyProviders.isNullOrBlank() &&
            ruleProviders.isNullOrBlank() &&
            relayGroups.isEmpty() &&
            proxyChain.isEmpty() &&
            (script == null || script.source.isBlank())

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * A user-authored JS script that rewrites the composed config, using the de-facto contract
 * shared across Clash clients:
 *
 * ```js
 * function main(config) { config.dns.enable = true; return config }
 * ```
 *
 * Stored **inside** the layer rather than as a sibling `user_script.js`, so it inherits the
 * capture/restore the layer already gets on subscription update for free — surviving an update
 * is the whole point of a user script, and a second file would be one more thing every commit
 * path has to remember to move. The cost is a JS blob living in JSON, which is unpleasant to
 * read by hand but is never hand-edited.
 *
 * [enabled] is kept separate from an empty [source] so a user can switch a script off while
 * still keeping the text around.
 */
@Serializable
data class UserScript(
    val source: String = "",
    val enabled: Boolean = true,
) {
    /** The text to actually run — blank when there is nothing to do. */
    val effective: String
        get() = if (enabled) source else ""
}

/** A user-created `select` proxy-group fed by proxy-providers (the relay feature). */
@Serializable
data class RelayGroup(
    val name: String,
    val providerKeys: List<String> = emptyList(),
)

/**
 * Reads/writes the per-profile [UserLayer] at `importedDir/<uuid>/user_layer.json`, mirroring how
 * the rule state and proxy-provider labels are already persisted next to `config.yaml`.
 */
class UserLayerStore(private val importedDir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private fun fileOf(uuid: UUID): File = File(importedDir, "$uuid/$FILE_NAME")

    /** The stored layer, or an empty layer when there is no file / it cannot be parsed. */
    fun load(uuid: UUID): UserLayer {
        val f = fileOf(uuid)
        if (!f.isFile) return UserLayer()
        return runCatching { json.decodeFromString(UserLayer.serializer(), f.readText()) }
            .getOrElse {
                Log.w("UserLayerStore: failed to parse ${f.name} for $uuid, treating as empty", it)
                UserLayer()
            }
    }

    /** Persists the layer; removes the file when the layer is empty (no edits to keep). */
    fun save(uuid: UUID, layer: UserLayer) {
        val f = fileOf(uuid)
        if (layer.isEmpty()) {
            f.delete()
            return
        }
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(UserLayer.serializer(), layer))
    }

    /** Mutate-and-save helper for per-section editors. */
    fun update(uuid: UUID, block: (UserLayer) -> UserLayer) {
        save(uuid, block(load(uuid)))
    }

    fun clear(uuid: UUID) {
        fileOf(uuid).delete()
    }

    fun exists(uuid: UUID): Boolean = fileOf(uuid).isFile

    companion object {
        const val FILE_NAME = "user_layer.json"

        private val flatJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Load the layer directly from a profile directory (flat, e.g. the processing dir). */
        fun loadAt(profileDir: File): UserLayer {
            val f = File(profileDir, FILE_NAME)
            if (!f.isFile) return UserLayer()
            return runCatching { flatJson.decodeFromString(UserLayer.serializer(), f.readText()) }
                .getOrElse { UserLayer() }
        }

        /** Save the layer directly into a profile directory (flat); empty layer removes the file. */
        fun saveAt(profileDir: File, layer: UserLayer) {
            val f = File(profileDir, FILE_NAME)
            if (layer.isEmpty()) {
                f.delete()
                return
            }
            f.parentFile?.mkdirs()
            f.writeText(flatJson.encodeToString(UserLayer.serializer(), layer))
        }
    }
}
