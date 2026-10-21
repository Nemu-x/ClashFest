package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.ProxyTransportInfo

internal fun proxyTransportLabel(type: Proxy.Type, info: ProxyTransportInfo?): String? {
    if (type in BUILTINS || type.group) return null
    if (type in QUIC) return "QUIC"
    if (type == Proxy.Type.WireGuard) return "UDP"
    if (info == null) return null
    return when (val network = info.network.lowercase()) {
        "" -> "TCP"
        "h2", "http2" -> "H2"
        else -> network.uppercase()
    }
}

internal fun proxyUsesTls(type: Proxy.Type, info: ProxyTransportInfo?): Boolean =
    type !in BUILTINS && !type.group &&
        (info?.tls == true || type in QUIC || type == Proxy.Type.Trojan || type == Proxy.Type.AnyTLS)

private val QUIC = setOf(Proxy.Type.Hysteria, Proxy.Type.Hysteria2, Proxy.Type.Tuic, Proxy.Type.Masque, Proxy.Type.ShadowQuic)
private val BUILTINS = setOf(Proxy.Type.Direct, Proxy.Type.Reject, Proxy.Type.RejectDrop, Proxy.Type.Compatible,
    Proxy.Type.Pass, Proxy.Type.PassRule, Proxy.Type.Rematch, Proxy.Type.Dns)
