package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.ConnectionMetadata
import com.github.kr328.clash.core.model.ConnectionTracker
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionFilterTest {
    private val direct = ConnectionTracker(id = "direct", chains = listOf("DIRECT"), metadata = ConnectionMetadata(network = "TCP"))
    private val proxy = ConnectionTracker(id = "proxy", chains = listOf("Japan"), metadata = ConnectionMetadata(network = "udp", host = "Example.org"))
    private val reject = ConnectionTracker(id = "blocked", providerChains = listOf("REJECT-DROP"))
    private val unknown = ConnectionTracker(id = "unknown")
    private val connections = listOf(direct, proxy, reject, unknown)

    @Test
    fun defaultViewDoesNotCopyOrInspectEveryConnection() {
        assertSame(connections, filterConnections(connections, ConnectionFilterMode.ALL, "  "))
    }

    @Test
    fun filtersKeepDirectProxyRejectedAndUnknownRoutesDistinct() {
        assertEquals(listOf(direct), filterConnections(connections, ConnectionFilterMode.DIRECT, ""))
        assertEquals(listOf(proxy), filterConnections(connections, ConnectionFilterMode.PROXY, ""))
        assertEquals(listOf(reject), filterConnections(connections, ConnectionFilterMode.REJECT, ""))
        assertEquals(listOf(direct), filterConnections(connections, ConnectionFilterMode.TCP, ""))
        assertEquals(listOf(proxy), filterConnections(connections, ConnectionFilterMode.UDP, ""))
    }

    @Test
    fun searchIsCaseInsensitiveTrimmedAndCombinedWithFilter() {
        assertEquals(listOf(proxy), filterConnections(connections, ConnectionFilterMode.ALL, " EXAMPLE.ORG "))
        assertEquals(emptyList(), filterConnections(connections, ConnectionFilterMode.TCP, "example.org"))
        assertEquals(listOf(reject), filterConnections(connections, ConnectionFilterMode.ALL, "reject-drop"))
        assertEquals(listOf(unknown), filterConnections(connections, ConnectionFilterMode.ALL, "UNKNOWN"))
    }

    @Test
    fun searchIncludesProcessPathAndRulePayloadWithoutMetadata() {
        val process = proxy.copy(metadata = ConnectionMetadata(processPath = "/system/bin/Browser"))
        val rule = unknown.copy(rulePayload = "example.net")
        assertEquals(listOf(process), filterConnections(listOf(process, rule), ConnectionFilterMode.ALL, "browser"))
        assertEquals(listOf(rule), filterConnections(listOf(process, rule), ConnectionFilterMode.ALL, "EXAMPLE.NET"))
    }

    @Test
    fun obsoleteLargeFilterCanStopBeforeTraversingTheWholeSnapshot() {
        var checks = 0
        assertFailsWith<CancellationException> {
            filterConnections(List(1_000) { proxy }, ConnectionFilterMode.UDP, "missing") {
                if (++checks == 2) throw CancellationException()
            }
        }
        assertEquals(2, checks)
    }

    @Test
    fun newerInputAndDetachInvalidateOldResultsEvenBeforeNewResultFinishes() {
        val revisions = ConnectionListRevision()
        val oldSnapshot = revisions.next()
        assertTrue(revisions.isCurrent(oldSnapshot))
        val newSearch = revisions.next()
        assertFalse(revisions.isCurrent(oldSnapshot))
        assertTrue(revisions.isCurrent(newSearch))
        revisions.next()
        assertFalse(revisions.isCurrent(newSearch))
        val reattachedSnapshot = revisions.next()
        assertTrue(revisions.isCurrent(reattachedSnapshot))
    }
}
