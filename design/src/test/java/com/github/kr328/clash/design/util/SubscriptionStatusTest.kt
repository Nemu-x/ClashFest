package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.SubscriptionUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionStatusTest {
    @Test
    fun headerFieldsOverrideSavedUsageAndSecondsBecomeMilliseconds() {
        val status = subscriptionStatus(10, 20, 100, 5000, SubscriptionUsage.parse("upload=30; download=40; total=200; expire=2000"))
        assertEquals(130L, status.remainingBytes)
        assertEquals(2_000_000L, status.expireMs)
    }

    @Test
    fun missingHeaderFieldsFallBackIndividually() {
        val status = subscriptionStatus(10, 20, 100, 5000, SubscriptionUsage.parse("total=200"))
        assertEquals(170L, status.remainingBytes)
        assertEquals(5000L, status.expireMs)
    }

    @Test
    fun exhaustedQuotaDoesNotBecomeNegativeAndZeroMeansUnlimited() {
        assertEquals(0L, subscriptionStatus(70, 40, 100, 0, null).remainingBytes)
        assertNull(subscriptionStatus(70, 40, 0, 0, null).remainingBytes)
        assertEquals(0L, subscriptionStatus(0, 0, 0, 5000, SubscriptionUsage.parse("expire=0")).expireMs)
    }

    @Test
    fun oversizedOrNegativeMetadataCannotOverflowRemainingTrafficOrExpiry() {
        val status = subscriptionStatus(10, 20, 100, 5000, SubscriptionUsage(-1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, status.remainingBytes)
        assertEquals(Long.MAX_VALUE, status.expireMs)
        assertEquals(70L, subscriptionStatus(10, 20, 100, 0, SubscriptionUsage(null, null, -1, null)).remainingBytes)
    }
}
