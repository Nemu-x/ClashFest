package com.github.kr328.clash.design.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidNavigationMotionTest {
    private val labels = listOf(49f, 58f, 51f, 64f)

    @Test fun theCapsuleSettlesWithoutOvershootingOrReversing() {
        assertEquals(0f, liquidNavigationProgress(-1f))
        assertEquals(1f, liquidNavigationProgress(2f))
        val samples = (0..1000).map { liquidNavigationProgress(it / 1000f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun everyLabelFitsWithFullTouchTargetsOnNarrowFourTabScreens() {
        for (selected in labels.indices) {
            val widths = requireNotNull(navigationWidths(280f, labels, selected, 48f, 56f))
            assertEquals(280f, widths.sum(), 0.01f)
            assertTrue(widths.all { it >= 48f })
            assertTrue(widths[selected] >= labels[selected] + 56f)
        }
    }

    @Test fun fiveTabsAndLargerTextUseTheFullWidthCaptionWhenNeeded() {
        assertNull(navigationWidths(280f, labels + 59f, 2, 48f, 56f))
        assertNull(navigationWidths(280f, labels.map { it * 1.6f }, 2, 48f, 56f))
        assertTrue(navigationWidths(320f, labels + 59f, 2, 48f, 56f) != null)
    }

    @Test fun interruptedTransitionsPreserveWidthsAndTouchTargets() {
        val from = requireNotNull(navigationWidths(328f, labels, 0, 48f, 56f))
        val to = requireNotNull(navigationWidths(328f, labels, 3, 48f, 56f))
        val interrupted = from.zip(to) { a, b -> a + (b - a) * liquidNavigationProgress(.35f) }
        val next = requireNotNull(navigationWidths(328f, labels, 1, 48f, 56f))
        for (step in 0..100) {
            val p = liquidNavigationProgress(step / 100f)
            val widths = interrupted.zip(next) { a, b -> a + (b - a) * p }
            assertEquals(328f, widths.sum(), .01f)
            assertTrue(widths.all { it >= 48f })
        }
    }
}
