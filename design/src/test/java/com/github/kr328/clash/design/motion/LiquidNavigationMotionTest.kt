package com.github.kr328.clash.design.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidNavigationMotionTest {
    @Test fun bothEdgesStartAndFinishAtTheActualTabBounds() {
        for (leading in listOf(false, true)) {
            assertEquals(0f, liquidNavigationProgress(-1f, leading))
            assertEquals(0f, liquidNavigationProgress(0f, leading))
            assertEquals(1f, liquidNavigationProgress(1f, leading))
            assertEquals(1f, liquidNavigationProgress(2f, leading))
        }
    }

    @Test fun theFrontPullsAheadAndTheTailCatchesUp() {
        assertTrue(liquidNavigationProgress(0.4f, true) > liquidNavigationProgress(0.4f, false))
        for (leading in listOf(false, true)) {
            val samples = (0..1000).map { liquidNavigationProgress(it / 1000f, leading) }
            assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(samples.all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test fun longJumpsInEitherDirectionNeverInvertTheConnectedShape() {
        for (forward in listOf(false, true)) {
            val fromLeft = if (forward) 0f else 300f
            val fromRight = if (forward) 80f else 360f
            val toLeft = if (forward) 300f else 0f
            val toRight = if (forward) 360f else 80f
            for (i in 0..1000) {
                val t = i / 1000f
                val left = fromLeft + (toLeft - fromLeft) * liquidNavigationProgress(t, !forward)
                val right = fromRight + (toRight - fromRight) * liquidNavigationProgress(t, forward)
                assertTrue(right - left >= 59.99f)
                assertTrue(left >= 0f && right <= 360f)
            }
        }
    }

    @Test fun theNeckRemainsConnectedAndSettlesWithoutAnIdleLoop() {
        for (initial in listOf(0f, 0.3f, 0.45f)) {
            val samples = (0..1000).map { liquidNavigationPinch(it / 1000f, initial, 0.38f) }
            assertEquals(initial, samples.first())
            assertEquals(0f, samples.last())
            assertTrue(samples.all { it.isFinite() && it in 0f..0.45f })
        }
    }
}
