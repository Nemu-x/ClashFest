package com.github.kr328.clash.design.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpressiveSpringTest {
    @Test fun springStartsAndSettlesExactlyAtTargets() {
        assertEquals(0f, expressiveSpringProgress(0f))
        assertEquals(1f, expressiveSpringProgress(1f))
        assertEquals(0f, expressiveSpringProgress(-1f))
        assertEquals(1f, expressiveSpringProgress(2f))
    }

    @Test fun spatialMotionOvershootsWithoutLeavingSafeShapeBounds() {
        val samples = (0..1000).map { expressiveSpringProgress(it / 1000f) }
        assertTrue(samples.max() > 1.05f)
        assertTrue(samples.all { it.isFinite() && it in 0f..1.25f })
        assertTrue(samples.takeLast(50).all { it in 0.99f..1.01f })
    }
}
