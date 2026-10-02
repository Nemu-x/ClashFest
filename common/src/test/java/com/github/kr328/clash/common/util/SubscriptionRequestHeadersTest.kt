package com.github.kr328.clash.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors native/useragent/useragent_test.go: the Kotlin probes must send the same UA as the Go fetch. */
class SubscriptionRequestHeadersTest {
    private val marzbanClashMeta = Regex("^([Cc]lash-verge|[Cc]lash[-.]?[Mm]eta|[Ff][Ll][Cc]lash|[Mm]ihomo)")
    private val marzbanLegacy = Regex("^([Cc]lash|[Ss]tash)")
    private val mikanCore = Regex("(mihomo|clash[.-]?meta)/v?(\\d+\\.\\d+\\.\\d+)")

    @Test
    fun withCoreTag() {
        assertEquals("mihomo/1.19.32 Mikan/1.1.1", SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1.Alpha", "v1.19.32"))
    }

    @Test
    fun withoutCoreTagSendsBareMihomo() {
        assertEquals("mihomo Mikan/1.1.1", SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1.Alpha.debug", null))
        assertEquals("mihomo Mikan/1.1.1", SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1", ""))
        assertEquals("mihomo Mikan/1.1.1", SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1", "Alpha"))
        assertEquals("mihomo Mikan/1.1.1", SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1", "v1.19.32-3-gabc"))
    }

    @Test
    fun matchesPanelFormatPickers() {
        for (ua in listOf(
            SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1", "v1.19.32"),
            SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1.Alpha", null),
        )) {
            assertTrue(marzbanClashMeta.containsMatchIn(ua), ua)
            assertFalse(marzbanLegacy.containsMatchIn(ua), ua)
        }
        assertEquals("1.19.32", mikanCore.find(SubscriptionRequestHeaders.buildDefaultUserAgent("1.1.1", "v1.19.32"))?.groupValues?.get(2))
    }

    @Test
    fun nonSemverVersionNamePassesThrough() {
        assertEquals("mihomo/1.19.32 Mikan/unknown", SubscriptionRequestHeaders.buildDefaultUserAgent("unknown", "1.19.32"))
    }
}
