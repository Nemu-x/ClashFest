package com.github.kr328.clash.design.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerPingModeTest {
    @Test
    fun directModeNeverDisplaysProxyHistory() {
        assertEquals(-1, ServerPingMode.ToServer.resolveDelay(null, 40, 50))
        assertEquals(12, ServerPingMode.ToServer.resolveDelay(12, 40, 50))
    }

    @Test
    fun throughProxyModeNeverDisplaysDirectProbe() {
        assertEquals(-1, ServerPingMode.ThroughServer.resolveDelay(12, -1, -1))
        assertEquals(40, ServerPingMode.ThroughServer.resolveDelay(12, 40, 50))
        assertEquals(50, ServerPingMode.ThroughServer.resolveDelay(12, -1, 50))
    }

    @Test
    fun failedAndUnsupportedDirectTestsKeepTheirStatus() {
        assertEquals(Int.MAX_VALUE, ServerPingMode.ToServer.resolveDelay(Int.MAX_VALUE, 40, 50))
        assertEquals(-2, ServerPingMode.ToServer.resolveDelay(-2, 40, 50))
    }
}
