package com.github.kr328.clash.design.util

import android.content.Context
import androidx.annotation.StringRes
import com.github.kr328.clash.core.model.DesyncCapabilities
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DesyncDeviceMethods
import com.github.kr328.clash.design.model.DesyncMethodLine
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
     * One line per method (see [DesyncDeviceMethods]): available, checked on this phone, checked on
     * each connection, or not available with a reason in plain words where the app knows one.
     */
    fun capabilityLines(context: Context, caps: DesyncCapabilities): List<String> =
        DesyncDeviceMethods.of(caps).map { line ->
            val name = methodTitle(line.key)?.let(context::getString) ?: line.key
            when (line.state) {
                DesyncMethodLine.State.Available -> context.getString(R.string.desync_method_available, name)
                DesyncMethodLine.State.Checked -> context.getString(R.string.desync_method_available_checked, name)
                DesyncMethodLine.State.PerConnection -> context.getString(R.string.desync_method_per_connection, name)
                DesyncMethodLine.State.Unavailable -> {
                    val reason = when {
                        line.key in caps.conditional -> context.getString(R.string.desync_reason_unsent_bytes)
                        line.key == "auto-ttl-ipv4" -> context.getString(R.string.desync_reason_auto_ttl_ipv4)
                        else -> line.reason
                    }
                    if (reason.isNullOrBlank()) {
                        context.getString(R.string.desync_method_unavailable_plain, name)
                    } else {
                        context.getString(R.string.desync_method_unavailable, name, reason)
                    }
                }
            }
        }

    @StringRes
    private fun methodTitle(key: String): Int? = when (key) {
        "ttl" -> R.string.desync_method_ttl
        "fake-tcp" -> R.string.desync_method_fake_tcp
        "fake-udp" -> R.string.desync_method_fake_udp
        "disorder" -> R.string.desync_method_disorder
        "split-wait-sent" -> R.string.desync_method_split_wait
        "oob" -> R.string.desync_method_oob
        "auto-ttl-ipv4" -> R.string.desync_method_auto_ttl_ipv4
        "auto-ttl-ipv6" -> R.string.desync_method_auto_ttl_ipv6
        else -> null
    }
}
