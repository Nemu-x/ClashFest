package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.ProxyTransportInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyTransportLabelTest {
    @Test
    fun hysteria2UsesQuicEvenWithoutNetworkMetadata() {
        assertEquals("QUIC", proxyTransportLabel(Proxy.Type.Hysteria2, ProxyTransportInfo(type = "hysteria2")))
        assertEquals("QUIC", proxyTransportLabel(Proxy.Type.Hysteria2, null))
        assertEquals("QUIC", proxyTransportLabel(Proxy.Type.Hysteria2, ProxyTransportInfo(network = "tcp")))
    }

    @Test
    fun vlessTransportAndTcpDefaultArePreserved() {
        assertEquals("TCP", proxyTransportLabel(Proxy.Type.Vless, ProxyTransportInfo()))
        assertEquals("GRPC", proxyTransportLabel(Proxy.Type.Vless, ProxyTransportInfo(network = "grpc")))
        assertEquals("WS", proxyTransportLabel(Proxy.Type.Vmess, ProxyTransportInfo(network = "ws")))
        assertEquals("H2", proxyTransportLabel(Proxy.Type.Trojan, ProxyTransportInfo(network = "http2")))
        assertNull(proxyTransportLabel(Proxy.Type.Vless, null))
    }
}
