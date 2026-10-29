package com.github.kr328.clash.service.model

import com.github.kr328.clash.core.model.DesyncConfigRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a "Desync only" profile is generated from (`Clash.generateDesyncOnlyConfig`): the profile
 * stores these settings and its config.yaml is regenerated from them, never fetched.
 * Stored as JSON in the `desyncSettings` column of `imported` / `pending`.
 */
@Serializable
data class DesyncProfileSettings(
    /** IDs of `Clash.desyncServices()`. */
    val services: List<String> = DEFAULT_SERVICES,
    /** Extra host names, routed with their subdomains. */
    val customDomains: List<String> = emptyList(),
    /** A TCP preset ID of `Clash.desyncPresets()`; blank = automatic (the catalog's default). */
    val strategyPresetId: String = AUTOMATIC,
    /** An optional UDP preset ID; blank = none (QUIC is then rejected so apps fall back to TCP). */
    val udpPresetId: String = "",
) {
    val isAutomaticStrategy: Boolean
        get() = strategyPresetId.isBlank()

    /** The bridge request: trimmed, blanks and duplicates dropped, order kept. */
    fun toRequest(): DesyncConfigRequest = DesyncConfigRequest(
        services = services.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        customDomains = customDomains.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        strategyPresetId = strategyPresetId.trim(),
        udpPresetId = udpPresetId.trim(),
        rejectQuic = null,
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** Blank strategy: the generator takes the catalog's `defaultTcp`. */
        const val AUTOMATIC = ""

        /** Checked on the setup screen: the two services the feature is mostly wanted for. */
        val DEFAULT_SERVICES: List<String> = listOf("youtube", "discord")

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Null for null, blank or malformed JSON (the profile is then treated as ordinary). */
        fun decode(raw: String?): DesyncProfileSettings? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
        }

        /** Splits user input (lines, commas, spaces) into domain entries; validation is the generator's. */
        fun parseDomains(text: String): List<String> =
            text.split('\n', ',', ' ', '\t', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
