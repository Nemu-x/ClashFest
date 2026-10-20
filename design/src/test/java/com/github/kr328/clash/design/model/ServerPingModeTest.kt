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
    fun throughProxyModePrefersTheRunningEngine() {
        assertEquals(40, ServerPingMode.ThroughServer.resolveDelay(12, 40, 50))
        assertEquals(50, ServerPingMode.ThroughServer.resolveDelay(null, -1, 50))
        assertEquals(-1, ServerPingMode.ThroughServer.resolveDelay(null, -1, -1))
    }

    @Test
    fun throughProxyModeShowsTheStandaloneMeasurementWithoutTheEngine() {
        // VPN off: the GET through the node ran in the core standalone and is the only value.
        assertEquals(12, ServerPingMode.ThroughServer.resolveDelay(12, -1, -1))
        assertEquals(12, ServerPingMode.ThroughServer.resolveDelay(12, -1, 50))
        assertEquals(Int.MAX_VALUE, ServerPingMode.ThroughServer.resolveDelay(Int.MAX_VALUE, -1, -1))
    }

    @Test
    fun failedAndUnsupportedDirectTestsKeepTheirStatus() {
        assertEquals(Int.MAX_VALUE, ServerPingMode.ToServer.resolveDelay(Int.MAX_VALUE, 40, 50))
        assertEquals(-2, ServerPingMode.ToServer.resolveDelay(-2, 40, 50))
    }
}
