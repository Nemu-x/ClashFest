package com.github.kr328.clash.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionMetaCacheTest {
    @Test
    fun completedAttemptThrottlesRegardlessOfReturnedQuota() {
        assertFalse(SubscriptionMetaCache.isRefreshDue("profile", "profile", 100L, 101L))
        assertFalse(SubscriptionMetaCache.isRefreshDue("profile", "profile", 100L, 21_699L))
    }

    @Test
    fun anotherProfileNeedsItsOwnRefresh() {
        assertTrue(SubscriptionMetaCache.isRefreshDue("other", "profile", 100L, 101L))
    }

    @Test
    fun firstAttemptAndExpiredAttemptAreDue() {
        assertTrue(SubscriptionMetaCache.isRefreshDue("profile", "profile", 0L, 101L))
        assertTrue(SubscriptionMetaCache.isRefreshDue("profile", "profile", 100L, 21_700L))
    }

    @Test
    fun clockMovedBackwardsDoesNotSuppressRefresh() {
        assertTrue(SubscriptionMetaCache.isRefreshDue("profile", "profile", 100L, 99L))
    }
}
