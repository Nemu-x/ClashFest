package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.ProxyTransportInfo

internal fun proxyTransportLabel(type: Proxy.Type, info: ProxyTransportInfo?): String? {
    if (type == Proxy.Type.Hysteria2) return "QUIC"
    if (info == null) return null
    return when (val network = info.network.lowercase()) {
        "" -> "TCP"
        "h2", "http2" -> "H2"
        else -> network.uppercase()
    }
}
