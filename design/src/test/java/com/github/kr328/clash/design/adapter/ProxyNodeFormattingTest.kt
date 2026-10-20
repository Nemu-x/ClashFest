package com.github.kr328.clash.design.adapter

import com.github.kr328.clash.service.model.ProxyTransportInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
