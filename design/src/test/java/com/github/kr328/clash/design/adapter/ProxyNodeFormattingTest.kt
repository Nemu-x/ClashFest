package com.github.kr328.clash.design.adapter

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.ProxyTransportInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyNodeFormattingTest {
    // VLESS over XDrive (core patch 0003): a TCP-stream transport through cloud storage. The
    // engine rejects outer TLS / REALITY on it, so it never earns a TLS badge.
    @Test
    fun xdriveIsItsOwnTransportWithoutTls() {
        val info = ProxyTransportInfo(network = "xdrive", type = "vless")
        assertEquals("XDRIVE", transportLabel(info, "Vless"))
        assertFalse(usesTls(info, "Vless"))
    }

    // A REJECT / DIRECT node has no `network:` key, which used to read as "TCP".
    @Test
    fun builtinOutboundsShowNoTransportOrTls() {
        val info = ProxyTransportInfo(tls = true)
        for (type in listOf(
            Proxy.Type.Direct, Proxy.Type.Reject, Proxy.Type.RejectDrop,
            Proxy.Type.Pass, Proxy.Type.PassRule, Proxy.Type.Compatible,
        )) {
            assertFalse(type.name, hasTransport(type.name))
            assertNull(type.name, transportLabel(info, type.name))
            assertFalse(type.name, usesTls(info, type.name))
        }
        assertTrue(hasTransport(Proxy.Type.Vless.name))
        assertEquals("TCP", transportLabel(ProxyTransportInfo(), Proxy.Type.Vless.name))
        assertEquals("TCP", transportLabel(ProxyTransportInfo(), Proxy.Type.Socks5.name))
    }
}
