package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.ProxyTransportInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyTransportLabelTest {
    @Test fun builtinsHaveNeitherTransportNorTlsEvenWithMetadata() {
        for (type in listOf(Proxy.Type.Direct, Proxy.Type.Reject, Proxy.Type.Pass, Proxy.Type.Rematch, Proxy.Type.Dns)) {
            assertNull(proxyTransportLabel(type, ProxyTransportInfo(tls = true)))
            assertFalse(proxyUsesTls(type, ProxyTransportInfo(tls = true)))
        }
    }

    @Test fun quicAndWireGuardAreNotMislabelledAsTcp() {
        assertEquals("QUIC", proxyTransportLabel(Proxy.Type.Tuic, ProxyTransportInfo()))
        assertEquals("QUIC", proxyTransportLabel(Proxy.Type.Masque, null))
        assertEquals("UDP", proxyTransportLabel(Proxy.Type.WireGuard, ProxyTransportInfo()))
        assertEquals("TCP", proxyTransportLabel(Proxy.Type.TrustTunnel, ProxyTransportInfo()))
    }

    @Test fun xdriveUsesItsOwnTransportAndNoOuterTls() {
        assertEquals("XDRIVE", proxyTransportLabel(Proxy.Type.Vless, ProxyTransportInfo(network = "xdrive")))
        assertFalse(proxyUsesTls(Proxy.Type.Vless, ProxyTransportInfo(network = "xdrive")))
        assertTrue(proxyUsesTls(Proxy.Type.Trojan, null))
    }

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
