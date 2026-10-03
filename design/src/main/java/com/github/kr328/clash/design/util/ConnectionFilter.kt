package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.ConnectionTracker

internal enum class ConnectionFilterMode { ALL, TCP, UDP, DIRECT, PROXY, REJECT }

internal class ConnectionListRevision {
    private var revision = 0L
    fun next(): Long = ++revision
    fun isCurrent(candidate: Long): Boolean = candidate == revision
}

internal fun filterConnections(
    connections: List<ConnectionTracker>,
    mode: ConnectionFilterMode,
    query: String,
    checkCancelled: () -> Unit = {},
): List<ConnectionTracker> {
    val q = query.trim()
    if (mode == ConnectionFilterMode.ALL && q.isEmpty()) return connections
    val result = ArrayList<ConnectionTracker>()
    connections.forEachIndexed { index, connection ->
        if (index % 64 == 0) checkCancelled()
        if (matchesConnectionFilter(connection, mode) && (q.isEmpty() || matchesConnectionQuery(connection, q))) {
            result.add(connection)
        }
    }
    return result
}

private fun matchesConnectionFilter(c: ConnectionTracker, mode: ConnectionFilterMode): Boolean = when (mode) {
    ConnectionFilterMode.ALL -> true
    ConnectionFilterMode.TCP -> c.metadata?.network.equals("tcp", ignoreCase = true)
    ConnectionFilterMode.UDP -> c.metadata?.network.equals("udp", ignoreCase = true)
    ConnectionFilterMode.DIRECT -> isDirect(c)
    ConnectionFilterMode.REJECT -> isRejected(c)
    ConnectionFilterMode.PROXY -> (c.chains.isNotEmpty() || c.providerChains.isNotEmpty()) && !isDirect(c) && !isRejected(c)
}

private fun isDirect(c: ConnectionTracker): Boolean =
    c.rule.equals("direct", ignoreCase = true) || c.chains.any { it.equals("direct", ignoreCase = true) }

private fun isRejected(c: ConnectionTracker): Boolean =
    c.rule.contains("reject", ignoreCase = true) ||
        c.chains.any { it.contains("reject", ignoreCase = true) } ||
        c.providerChains.any { it.contains("reject", ignoreCase = true) }

private fun matchesConnectionQuery(c: ConnectionTracker, q: String): Boolean {
    if (c.id.contains(q, ignoreCase = true) || c.rule.contains(q, ignoreCase = true) ||
        c.rulePayload.contains(q, ignoreCase = true) ||
        c.chains.any { it.contains(q, ignoreCase = true) } ||
        c.providerChains.any { it.contains(q, ignoreCase = true) }
    ) return true
    val m = c.metadata ?: return false
    return m.host.contains(q, ignoreCase = true) || m.process.contains(q, ignoreCase = true) ||
        m.processPath.contains(q, ignoreCase = true) || m.network.contains(q, ignoreCase = true) ||
        m.sniffHost.contains(q, ignoreCase = true) || m.destinationIP.contains(q, ignoreCase = true) ||
        m.sourceIP.contains(q, ignoreCase = true) || m.inboundName.contains(q, ignoreCase = true) ||
        m.remoteDestination.contains(q, ignoreCase = true)
}
