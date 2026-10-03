package com.github.kr328.clash.design.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickGuardTest {
    private var now = 0L
    private val guard = ClickGuard<String>(600L) { now }

    @Test
    fun rapidRepeatedClickIsRejected() {
        assertTrue(guard.accept("about"))
        now = 599L
        assertFalse(guard.accept("about"))
        now = 600L
        assertTrue(guard.accept("about"))
    }

    @Test
    fun rejectedClicksDoNotExtendCooldown() {
        assertTrue(guard.accept("connect"))
        for (time in listOf(100L, 300L, 500L)) {
            now = time
            assertFalse(guard.accept("connect"))
        }
        now = 600L
        assertTrue(guard.accept("connect"))
    }

    @Test
    fun differentActionsRemainAvailable() {
        assertTrue(guard.accept("about"))
        assertTrue(guard.accept("settings"))
        assertTrue(guard.accept("connect"))
    }

    @Test
    fun openDialogRejectsClicksEvenAfterCooldown() {
        assertTrue(guard.accept("about"))
        assertTrue(guard.tryOpen("about"))
        now = 10_000L
        assertTrue(guard.isOpen("about"))
        assertFalse(guard.accept("about"))
        assertFalse(guard.tryOpen("about"))
        assertTrue(guard.accept("settings"))
    }

    @Test
    fun dismissedDialogCanOpenAgain() {
        assertTrue(guard.accept("about"))
        assertTrue(guard.tryOpen("about"))
        now = 1_000L
        guard.close("about")
        assertFalse(guard.isOpen("about"))
        assertTrue(guard.accept("about"))
        assertTrue(guard.tryOpen("about"))
    }

    @Test
    fun quickDismissalDoesNotBypassClickCooldown() {
        assertTrue(guard.accept("about"))
        assertTrue(guard.tryOpen("about"))
        guard.close("about")
        now = 100L
        assertFalse(guard.accept("about"))
        now = 600L
        assertTrue(guard.accept("about"))
    }

    @Test
    fun queuedOrDirectOpenCannotCreateSecondDialog() {
        assertTrue(guard.tryOpen("about"))
        assertFalse(guard.tryOpen("about"))
        guard.close("about")
        assertTrue(guard.tryOpen("about"))
    }
}
