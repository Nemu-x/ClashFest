package com.github.kr328.clash.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapsTest {
    @Test
    fun smallImagesAreNotSampled() {
        assertEquals(1, sampleSizeFor(200, 200, 256))
        assertEquals(1, sampleSizeFor(511, 300, 256))
    }

    @Test
    fun largerSideStaysAtLeastTheTarget() {
        assertEquals(2, sampleSizeFor(512, 512, 256))
        assertEquals(4, sampleSizeFor(1024, 1024, 256))
        assertEquals(16, sampleSizeFor(4096, 4096, 256))
    }

    @Test
    fun wideLogoIsSampledByItsLongSide() {
        assertEquals(2, sampleSizeFor(4000, 300, 1024))
    }
}
