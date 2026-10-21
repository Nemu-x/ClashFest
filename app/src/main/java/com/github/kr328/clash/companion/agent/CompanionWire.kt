package com.github.kr328.clash.companion.agent

import java.util.Calendar
import java.util.TimeZone

/** Conversions from the engine's representations to the wire's integers (PROTOCOL.md §8.6). */
internal object CompanionWire {
    private val RFC3339 = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?([Zz]|[+-]\\d{2}:\\d{2})$",
    )

    /**
     * Unix milliseconds of an RFC 3339 timestamp as Go's `time.Time` marshals it (connection
     * `start`), or 0 when it cannot be read. Hand-rolled: `java.time` needs API 26, minSdk is 21.
     */
    fun rfc3339Millis(raw: String): Long {
        val m = RFC3339.matchEntire(raw.trim()) ?: return 0L
        val g = m.groupValues
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        val fraction = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toLong() ?: 0L
        val zone = g[8]
        val offsetMinutes = if (zone.equals("Z", ignoreCase = true)) {
            0
        } else {
            val sign = if (zone[0] == '-') -1 else 1
            sign * (zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt())
        }
        return (calendar.timeInMillis + fraction - offsetMinutes * 60_000L).coerceAtLeast(0L)
    }

    /**
     * The engine's packed traffic value (`upload << 32 | download`, each scaled by
     * `down_scale_traffic` in core/src/main/cpp/bridge_helper.c) back to bytes: a 2-bit unit
     * (B, KiB, MiB, GiB) over a 30-bit value that carries hundredths for the scaled units.
     */
    fun unpackTraffic(packed: Long): Pair<Long, Long> = unscale(packed ushr 32) to unscale(packed and 0xFFFFFFFFL)

    private fun unscale(value: Long): Long {
        val data = value and 0x3FFFFFFFL
        return when ((value ushr 30) and 0x3L) {
            0L -> data
            1L -> data * 1024L / 100L
            2L -> data * 1024L * 1024L / 100L
            else -> data * 1024L * 1024L * 1024L / 100L
        }
    }
}
