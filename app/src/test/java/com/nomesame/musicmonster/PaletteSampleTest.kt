package com.nomesame.musicmonster

import com.nomesame.musicmonster.ui.theme.computeInSampleSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the power-of-two downscale factor used for background images. */
class PaletteSampleTest {

    @Test
    fun noDownscaleWhenSmallerThanTarget() {
        assertEquals(1, computeInSampleSize(200, 100, 256))
        assertEquals(1, computeInSampleSize(256, 256, 256))
    }

    @Test
    fun downscalesByPowersOfTwo() {
        // longest edge 512 -> /2 = 256 <= 256
        assertEquals(2, computeInSampleSize(512, 300, 256))
        // longest edge 1024 -> /4 = 256
        assertEquals(4, computeInSampleSize(1024, 500, 256))
        // longest edge 4000 -> /16 = 250 <= 256
        assertEquals(16, computeInSampleSize(4000, 3000, 256))
    }

    @Test
    fun usesLongestEdge() {
        assertEquals(4, computeInSampleSize(100, 1024, 256))
    }

    @Test
    fun guardsInvalidTarget() {
        assertEquals(1, computeInSampleSize(4000, 3000, 0))
    }
}
