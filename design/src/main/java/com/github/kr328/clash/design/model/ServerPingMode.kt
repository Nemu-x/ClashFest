package com.github.kr328.clash.design.model

enum class ServerPingMode {
    ToServer,
    ThroughServer;

    fun resolveDelay(directDelay: Int?, proxyDelay: Int, groupDelay: Int): Int = when (this) {
        ToServer -> directDelay ?: -1
        // The running engine's own delay wins; with the VPN off (or another subscription
        // connected) the node was measured standalone through the core and lands in directDelay.
        ThroughServer -> when {
            proxyDelay >= 0 -> proxyDelay
            directDelay != null -> directDelay
            else -> groupDelay
        }
    }
}
