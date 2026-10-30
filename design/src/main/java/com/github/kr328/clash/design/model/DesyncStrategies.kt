package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.DesyncPreset
import com.github.kr328.clash.core.model.DesyncPresetCatalog
import com.github.kr328.clash.core.model.DesyncService
import com.github.kr328.clash.core.model.DesyncStatus
import com.github.kr328.clash.service.model.DesyncProfileSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * How a strategy reshapes the first TLS message, for the plain-language picker. Declaration order is
 * the order of the picker's groups: the gentlest methods first, decoys (which need TTL control) last.
 */
enum class DesyncStrategyKind {
    RecordSplit,
    PacketSplit,
    Combined,
    Random,
    Disorder,
    OutOfBand,
    Decoy,
    UdpDecoy,
}

/** A catalog preset with its kind and its 1-based number within that kind (catalog order). */
data class DesyncStrategyEntry(
    val preset: DesyncPreset,
    val kind: DesyncStrategyKind,
    val variant: Int,
)

object DesyncStrategies {
    /** Classifies a preset by the outbound keys it sets (`tlsrec`, `split`, `fake`, ...). */
    fun kindOf(preset: DesyncPreset, udp: Boolean = false): DesyncStrategyKind {
        if (udp) return DesyncStrategyKind.UdpDecoy
        val o = preset.options
        fun set(key: String): Boolean {
            val v = o[key] ?: return false
            return (v as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: p.content.isNotBlank() } ?: true
        }
        return when {
            set("fake") -> DesyncStrategyKind.Decoy
            set("disorder") -> DesyncStrategyKind.Disorder
            set("oob") -> DesyncStrategyKind.OutOfBand
            set("random") -> DesyncStrategyKind.Random
            set("tlsrec") && set("split") -> DesyncStrategyKind.Combined
            set("tlsrec") -> DesyncStrategyKind.RecordSplit
            else -> DesyncStrategyKind.PacketSplit
        }
    }

    /** [presets] grouped by kind (in [DesyncStrategyKind] order), catalog order kept inside a group. */
    fun group(presets: List<DesyncPreset>, udp: Boolean = false): List<Pair<DesyncStrategyKind, List<DesyncStrategyEntry>>> {
        val byKind = presets.groupBy { kindOf(it, udp) }
        return DesyncStrategyKind.values().mapNotNull { kind ->
            val list = byKind[kind] ?: return@mapNotNull null
            kind to list.mapIndexed { i, p -> DesyncStrategyEntry(p, kind, i + 1) }
        }
    }

    /** The TCP or UDP entry with [id], numbered as in [group]. */
    fun entryFor(catalog: DesyncPresetCatalog, id: String): DesyncStrategyEntry? {
        if (id.isBlank()) return null
        group(catalog.tcp).flatMap { it.second }.firstOrNull { it.preset.id == id }?.let { return it }
        return group(catalog.udp, udp = true).flatMap { it.second }.firstOrNull { it.preset.id == id }
    }

    /** The TCP strategy a profile runs: its own, or the catalog default when automatic. */
    fun effectiveTcp(catalog: DesyncPresetCatalog, strategyPresetId: String): DesyncStrategyEntry? =
        entryFor(catalog, strategyPresetId.ifBlank { catalog.defaultTcp })
}

/**
 * The editable state of the Desync setup / settings screen. Built with [from], which drops what the
 * running library no longer offers (a removed service, a preset of another catalog) so that saving
 * never sends the generator an ID it rejects.
 */
class DesyncSettingsForm private constructor(
    private val serviceOrder: List<String>,
    selected: Collection<String>,
    var customDomains: List<String>,
    var strategyPresetId: String,
    var udpPresetId: String,
) {
    private val selected = linkedSetOf<String>().apply { addAll(selected) }

    fun isSelected(service: String): Boolean = service in selected

    fun setSelected(service: String, on: Boolean) {
        if (service !in serviceOrder) return
        if (on) selected += service else selected -= service
    }

    /** Nothing would be routed: the generator refuses such a profile. */
    val isEmpty: Boolean
        get() = selected.isEmpty() && customDomains.none { it.isNotBlank() }

    fun toSettings(): DesyncProfileSettings = DesyncProfileSettings(
        services = serviceOrder.filter { it in selected },
        customDomains = customDomains.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        strategyPresetId = strategyPresetId,
        udpPresetId = udpPresetId,
    )

    companion object {
        fun from(
            settings: DesyncProfileSettings,
            services: List<DesyncService>,
            catalog: DesyncPresetCatalog?,
        ): DesyncSettingsForm {
            val order = services.map { it.id }
            val tcp = settings.strategyPresetId.takeIf { id -> catalog?.tcp?.any { it.id == id } == true }
                ?: DesyncProfileSettings.AUTOMATIC
            val udp = settings.udpPresetId.takeIf { id -> catalog?.udp?.any { it.id == id } == true } ?: ""
            return DesyncSettingsForm(
                serviceOrder = order,
                selected = settings.services.filter { it in order },
                customDomains = settings.customDomains,
                strategyPresetId = tcp,
                udpPresetId = udp,
            )
        }
    }
}

/** What the Home card says about a Desync-only profile's runtime. */
sealed class DesyncRuntimeSummary {
    /** The core is not running. */
    object Stopped : DesyncRuntimeSummary()

    /** Running, but no desync proxy is loaded (another profile is loading, or the load failed). */
    object NotLoaded : DesyncRuntimeSummary()

    /** Running with [routes] routes (one per service, plus one for custom domains). */
    data class Active(val routes: Int) : DesyncRuntimeSummary()

    companion object {
        fun of(running: Boolean, status: DesyncStatus?): DesyncRuntimeSummary = when {
            !running -> Stopped
            status == null || status.proxies.isEmpty() -> NotLoaded
            else -> Active(status.routes.size.takeIf { it > 0 } ?: status.proxies.size)
        }
    }
}
