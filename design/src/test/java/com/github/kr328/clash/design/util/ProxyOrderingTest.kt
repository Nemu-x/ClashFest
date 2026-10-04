package com.github.kr328.clash.design.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyOrderingTest {
    @Test
    fun movingServerPreservesOthersAndDoesNotMutateSubscriptionOrder() {
        val original = listOf("Auto", "Germany", "Poland", "France")
        val moved = ProxyOrdering.move(original, "Poland", -1)
        assertEquals(listOf("Auto", "Poland", "Germany", "France"), moved)
        assertEquals(listOf("Auto", "Germany", "Poland", "France"), original)
        assertEquals(listOf("Auto", "Germany", "Poland", "France"), ProxyOrdering.move(moved, "Poland", 1))
    }

    @Test
    fun changedSubscriptionDropsRemovedNamesAndAppendsNewServers() {
        assertEquals(listOf("Poland", "Germany", "France"), ProxyOrdering.resolve(
            listOf("Poland", "removed", "Germany", "Poland"), listOf("Germany", "Poland", "France"),
        ))
    }

    @Test
    fun boundariesAndUnknownServerLeaveOrderUnchanged() {
        val names = listOf("Germany", "Poland")
        assertEquals(names, ProxyOrdering.move(names, "Germany", -1))
        assertEquals(names, ProxyOrdering.move(names, "Poland", 1))
        assertEquals(names, ProxyOrdering.move(names, "missing", -1))
    }
}
