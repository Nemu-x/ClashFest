package com.github.kr328.clash.service.util

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionMaintenanceTest {
    @Test fun connectingDoesNotMoveTheLastFetchTime() {
        val dir = Files.createTempDirectory("subscription-clock").toFile()
        try {
            File(dir, ProfileComposer.SUBSCRIPTION_FILE).apply { writeText("base"); setLastModified(10_000L) }
            File(dir, "config.yaml").apply { writeText("composed"); setLastModified(90_000L) }
            assertEquals(10_000L, lastSubscriptionFetchAt(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun legacyProfilesUseConfigTimeAndMissingProfilesHaveNoClock() {
        val dir = Files.createTempDirectory("legacy-clock").toFile()
        try {
            assertEquals(0L, lastSubscriptionFetchAt(dir))
            File(dir, "config.yaml").apply { writeText("legacy"); setLastModified(20_000L) }
            assertEquals(20_000L, lastSubscriptionFetchAt(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun deletingTheActiveProfileSelectsTheFirstRemainingProfile() {
        val deleted = UUID(0, 1); val next = UUID(0, 2)
        assertEquals(next, activeProfileAfterDelete(deleted, deleted) { listOf(deleted, next, UUID(0, 3)) })
        assertNull(activeProfileAfterDelete(deleted, deleted) { emptyList() })
    }

    @Test fun deletingAnInactiveProfilePreservesSelectionWithoutQueryingTheList() {
        val active = UUID(0, 1)
        assertEquals(active, activeProfileAfterDelete(active, UUID(0, 2)) { error("must not query") })
        assertNull(activeProfileAfterDelete(null, UUID(0, 2)) { error("must not query") })
    }
}
