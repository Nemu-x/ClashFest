package com.github.kr328.clash.service.branding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrandLogoCacheTest {
    private val ttl = 6L * 60L * 60L * 1000L
    private val now = 10L * ttl

    @Test
    fun unchangedLogoIsReusedWithinSixHours() {
        assertTrue(BrandLogoFetcher.isCacheFresh(true, 100L, now - ttl + 1L, now))
    }

    @Test
    fun sameUrlIsFetchedAgainWhenTtlExpires() {
        assertFalse(BrandLogoFetcher.isCacheFresh(true, 100L, now - ttl, now))
    }

    @Test
    fun missingEmptyOversizedOrFutureDatedFilesAreNotReused() {
        assertFalse(BrandLogoFetcher.isCacheFresh(false, 100L, now, now))
        assertFalse(BrandLogoFetcher.isCacheFresh(true, 0L, now, now))
        assertFalse(BrandLogoFetcher.isCacheFresh(true, 512L * 1024L + 1L, now, now))
        assertFalse(BrandLogoFetcher.isCacheFresh(true, 100L, now + 1L, now))
        assertFalse(BrandLogoFetcher.isCacheFresh(true, 100L, 0L, now))
    }
}
