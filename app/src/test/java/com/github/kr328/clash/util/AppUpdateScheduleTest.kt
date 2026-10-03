package com.github.kr328.clash.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateScheduleTest {
    private val minute = 60_000L
    private val day = 24L * 60L * minute
    private val now = 10L * day

    @Test
    fun firstInstallWaitsThirtyMinutes() {
        assertEquals(now + 30L * minute, AppUpdateChecker.nextPeriodicCheckAt(now, 0L, 0L))
    }

    @Test
    fun coldStartPreservesThePendingDeadline() {
        val next = now + 5L * minute
        assertEquals(next, AppUpdateChecker.nextPeriodicCheckAt(now, 0L, next))
        assertEquals(next, AppUpdateChecker.nextPeriodicCheckAt(now + minute, 0L, next))
    }

    @Test
    fun coldStartForDueAlarmAdvancesByADayInsteadOfThirtyMinutes() {
        assertEquals(now + day, AppUpdateChecker.nextPeriodicCheckAt(now, 0L, now))
        assertEquals(now + day, AppUpdateChecker.nextPeriodicCheckAt(now, now - day, now - minute))
    }

    @Test
    fun migrationUsesTheLastCheckInsteadOfRestartingTheFirstDelay() {
        assertEquals(now + day - minute, AppUpdateChecker.nextPeriodicCheckAt(now, now - minute, 0L))
    }

    @Test
    fun repeatedAlarmColdStartsKeepDailyCadence() {
        val first = AppUpdateChecker.nextPeriodicCheckAt(now, 0L, 0L)
        val second = AppUpdateChecker.nextPeriodicCheckAt(first, 0L, first)
        assertEquals(first + day, second)
        assertEquals(second, AppUpdateChecker.nextPeriodicCheckAt(first + minute, first, second))
        assertEquals(second + day, AppUpdateChecker.nextPeriodicCheckAt(second, first, second))
    }

    @Test
    fun clockRollbackCannotDelayCheckingForMoreThanADay() {
        assertEquals(now + day, AppUpdateChecker.nextPeriodicCheckAt(now, now + day, now + 2L * day))
    }

    @Test
    fun foregroundAndAlarmChecksShareTheSixHourCooldown() {
        assertTrue(AppUpdateChecker.isCheckDue(now, 0L))
        assertFalse(AppUpdateChecker.isCheckDue(now, now - 6L * 60L * minute + 1L))
        assertTrue(AppUpdateChecker.isCheckDue(now, now - 6L * 60L * minute))
        assertTrue(AppUpdateChecker.isCheckDue(now, now + 1L))
    }
}
