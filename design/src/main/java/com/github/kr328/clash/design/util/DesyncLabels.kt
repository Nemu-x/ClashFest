package com.github.kr328.clash.design.util

import android.content.Context
import androidx.annotation.StringRes
import com.github.kr328.clash.core.model.DesyncCapabilities
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DesyncStrategyEntry
import com.github.kr328.clash.design.model.DesyncStrategyKind

/** Plain-language names for desync strategies and capabilities (the catalog itself is technical). */
object DesyncLabels {
    @StringRes
    fun kindTitle(kind: DesyncStrategyKind): Int = when (kind) {
        DesyncStrategyKind.RecordSplit -> R.string.desync_kind_record_split
        DesyncStrategyKind.PacketSplit -> R.string.desync_kind_packet_split
        DesyncStrategyKind.Combined -> R.string.desync_kind_combined
        DesyncStrategyKind.Random -> R.string.desync_kind_random
        DesyncStrategyKind.Disorder -> R.string.desync_kind_disorder
        DesyncStrategyKind.OutOfBand -> R.string.desync_kind_oob
        DesyncStrategyKind.Decoy -> R.string.desync_kind_decoy
        DesyncStrategyKind.UdpDecoy -> R.string.desync_kind_udp_decoy
    }

    /** "Split the TLS record, variant 2"; [entry] null = a preset this catalog does not have. */
    fun strategyName(context: Context, entry: DesyncStrategyEntry?): String {
        entry ?: return context.getString(R.string.desync_strategy_unknown)
        return context.getString(R.string.desync_strategy_variant, context.getString(kindTitle(entry.kind)), entry.variant)
    }

    /** The library's technical name and description, shown under the plain name. */
    fun strategyDetail(entry: DesyncStrategyEntry): String =
        "${entry.preset.name}: ${entry.preset.description}"

    /**
     * One line per method the library reports on, with the library's reason for a missing one.
     * Capabilities are static per library version, so the wording says "available", never "works".
     */
    fun capabilityLines(context: Context, caps: DesyncCapabilities): List<String> {
        val methods = listOf(
            Triple(R.string.desync_method_ttl, caps.ttlControl, "ttl"),
            Triple(R.string.desync_method_fake_tcp, caps.fakeTcp, "fake-tcp"),
            Triple(R.string.desync_method_fake_udp, caps.fakeUdp, "fake-udp"),
            Triple(R.string.desync_method_disorder, caps.disorder.supported, "disorder"),
            Triple(R.string.desync_method_oob, caps.oob, "oob"),
            Triple(R.string.desync_method_auto_ttl_ipv4, caps.autoTtl.ipv4, "auto-ttl-ipv4"),
            Triple(R.string.desync_method_auto_ttl_ipv6, caps.autoTtl.ipv6, "auto-ttl-ipv6"),
        )
        val known = methods.map { it.third }.toSet()
        val lines = methods.map { (title, supported, key) ->
            val name = context.getString(title)
            val reason = caps.unsupported[key]?.takeIf { it.isNotBlank() }
            when {
                supported && reason == null -> context.getString(R.string.desync_method_available, name)
                reason != null -> context.getString(R.string.desync_method_unavailable, name, reason)
                else -> context.getString(R.string.desync_method_unavailable_plain, name)
            }
        }
        // Methods a newer library reports that this app has no name for yet: shown as the library names them.
        val extra = caps.unsupported.filterKeys { it !in known }.map { (key, reason) ->
            context.getString(R.string.desync_method_unavailable, key, reason)
        }
        return lines + extra
    }
}
