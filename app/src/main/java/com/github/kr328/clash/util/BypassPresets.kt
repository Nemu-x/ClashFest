package com.github.kr328.clash.util

import android.content.Context
import android.content.pm.PackageManager
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Regional bypass preset: apps that usually need direct ISP access instead of
 * the VPN tunnel (banks, government, marketplaces, carriers of one region).
 *
 * Presets are data files in `assets/bypass_presets/<id>.json`, so the lists can
 * grow without code changes and the same format can later be fetched from an
 * online source. A package matches if it is listed explicitly or starts with
 * one of [prefixes] — the prefixes future-proof against apps missing from the
 * explicit list.
 */
@Serializable
data class BypassPreset(
    val id: String,
    val version: Int = 1,
    val title: String = "",
    val prefixes: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
) {
    private val packageSet: Set<String> by lazy { packages.toHashSet() }

    fun matches(packageName: String): Boolean =
        packageName in packageSet || prefixes.any { packageName.startsWith(it) }

    /** Returns the subset of installed packages matched by this preset. */
    fun installed(pm: PackageManager): Set<String> =
        pm.getInstalledPackages(0)
            .asSequence()
            .map { it.packageName }
            .filter(::matches)
            .toCollection(LinkedHashSet())
}

object BypassPresets {
    private const val ASSETS_DIR = "bypass_presets"

    /** Prompt only when a preset matches at least this many installed apps. */
    const val MIN_PROMPT_MATCHES = 3

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<BypassPreset> {
        val assets = context.assets
        val files = runCatching { assets.list(ASSETS_DIR)?.toList() }
            .getOrNull().orEmpty().filter { it.endsWith(".json") }

        return files.mapNotNull { name ->
            runCatching {
                assets.open("$ASSETS_DIR/$name").bufferedReader().use { reader ->
                    json.decodeFromString<BypassPreset>(reader.readText())
                }
            }.onFailure {
                Log.w("Ignore malformed bypass preset $name", it)
            }.getOrNull()
        }
    }

    /** Localized preset name, falling back to the title embedded in the JSON. */
    fun displayTitle(context: Context, preset: BypassPreset): String {
        val id = context.resources.getIdentifier(
            "bypass_preset_title_${preset.id}",
            "string",
            context.packageName,
        )
        return if (id != 0) context.getString(id) else preset.title.ifBlank { preset.id }
    }

    /**
     * The preset with the most installed matches, or null when even the best
     * one matches fewer than [MIN_PROMPT_MATCHES] apps. Installed apps are a
     * stronger signal than SIM country or locale: 20 Russian apps on the
     * phone means the user needs the RU bypass no matter where they are.
     */
    fun bestInstalled(context: Context, pm: PackageManager): Pair<BypassPreset, Set<String>>? =
        load(context)
            .map { it to it.installed(pm) }
            .maxByOrNull { it.second.size }
            ?.takeIf { it.second.size >= MIN_PROMPT_MATCHES }

    /**
     * Merges the preset's installed apps into the persisted access-control
     * selection and switches the mode to bypass. Additive on purpose: manual
     * user edits survive, re-applying only pulls in packages that are new in
     * the list or newly installed. Returns the number of added packages.
     */
    fun applyToStore(service: ServiceStore, pm: PackageManager, preset: BypassPreset): Int {
        val merged = service.accessControlPackages.toMutableSet()
        val before = merged.size
        merged.addAll(preset.installed(pm))
        service.accessControlPackages = merged
        service.accessControlMode = AccessControlMode.DenySelected
        service.bypassPresetSeeded = true
        return merged.size - before
    }
}
