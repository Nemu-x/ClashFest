package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.util.TunnelModeApplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanionWireTest {
    @Test
    fun rfc3339AsGoMarshalsTime() {
        assertEquals(1792540800123L, CompanionWire.rfc3339Millis("2026-10-21T00:00:00.123456789Z"))
        assertEquals(1792540800123L, CompanionWire.rfc3339Millis("2026-10-21T03:00:00.123+03:00"))
        assertEquals(1792540800000L, CompanionWire.rfc3339Millis("2026-10-20T19:00:00-05:00"))
        assertEquals(0L, CompanionWire.rfc3339Millis(""))
        assertEquals(0L, CompanionWire.rfc3339Millis("yesterday"))
    }

    @Test
    fun packedTrafficUnscalesToBytes() {
        // bridge_helper.c: <=1024 raw bytes; above that hundredths of KiB / MiB / GiB.
        val raw = 512L
        val kib = (150L * 100L / 100L) or (1L shl 30) // 1.50 KiB -> 150 hundredths
        assertEquals(raw to 1536L, CompanionWire.unpackTraffic((raw shl 32) or kib))
        val mib = 250L or (2L shl 30) // 2.50 MiB
        assertEquals(0L to 2_621_440L, CompanionWire.unpackTraffic(mib))
    }

    @Test
    fun tunnelModeFoldsDirectIntoRule() {
        assertEquals(TunnelState.Mode.Rule, TunnelModeApplier.normalize(TunnelState.Mode.Direct))
        assertEquals(TunnelState.Mode.Global, TunnelModeApplier.normalize(TunnelState.Mode.Global))
        assertEquals(TunnelState.Mode.Rule, TunnelModeApplier.parse("Direct"))
        assertEquals(TunnelState.Mode.Global, TunnelModeApplier.parse("Global"))
        assertNull(TunnelModeApplier.parse(""))
    }
}
