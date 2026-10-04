package com.github.kr328.clash.design.model

enum class ServerPingMode {
    ToServer,
    ThroughServer;

    fun resolveDelay(directDelay: Int?, proxyDelay: Int, groupDelay: Int): Int = when (this) {
        ToServer -> directDelay ?: -1
        ThroughServer -> if (proxyDelay >= 0) proxyDelay else groupDelay
    }
}
