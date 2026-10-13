package com.nomesame.musicmonster

import org.junit.Assert.*
import org.junit.Test

class SeekStepTest {
    @Test fun optionsAreFiveSecondStepsWithTenSecondDefault() {
        assertEquals(5, SeekStep.options.first())
        assertEquals(300, SeekStep.options.last())
        assertEquals(60, SeekStep.options.size)
        assertTrue(SeekStep.options.contains(SeekStep.DEFAULT_SECONDS))
        assertTrue(SeekStep.options.zipWithNext().all { (a, b) -> b - a == 5 })
    }
    @Test fun invalidIntervalsFallBackAndAllPickerValuesAreValid() {
        listOf(Int.MIN_VALUE, -5, 0, 1, 6, 301, Int.MAX_VALUE).forEach {
            assertEquals(10, SeekStep.validSeconds(it))
        }
        SeekStep.options.forEach { assertEquals(it, SeekStep.validSeconds(it)) }
    }
    @Test fun jumpsUseCurrentPositionInBothDirectionsAndCustomInterval() {
        assertEquals(30_000L, SeekStep.target(20_000, 90_000, 10, true))
        assertEquals(10_000L, SeekStep.target(20_000, 90_000, 10, false))
        assertEquals(45_000L, SeekStep.target(20_000, 90_000, 25, true))
        assertEquals(0L, SeekStep.target(20_000, 90_000, 25, false))
    }
    @Test fun songBoundariesNeverWrapToAnotherSong() {
        assertEquals(0L, SeekStep.target(2_000, 60_000, 10, false))
        assertEquals(59_999L, SeekStep.target(55_000, 60_000, 10, true))
        assertEquals(0L, SeekStep.target(0, 1, 300, true))
        assertEquals(0L, SeekStep.target(-10, 60_000, 10, false))
        assertEquals(59_999L, SeekStep.target(Long.MAX_VALUE, 60_000, 10, true))
    }
    @Test fun unknownDurationCannotSeekAndLongPositionsCannotOverflow() {
        listOf(Long.MIN_VALUE, -1, 0).forEach { assertNull(SeekStep.target(123, it, 10, true)) }
        assertEquals(Long.MAX_VALUE - 1, SeekStep.target(Long.MAX_VALUE - 10, Long.MAX_VALUE, 300, true))
        assertEquals(0L, SeekStep.target(Long.MIN_VALUE, Long.MAX_VALUE, 300, false))
    }
    @Test fun repeatedPressesAccumulateAndRespectBothEnds() {
        var position = 15_000L
        repeat(3) { position = SeekStep.target(position, 60_000, 10, true)!! }
        assertEquals(45_000L, position)
        repeat(20) { position = SeekStep.target(position, 60_000, 10, true)!! }
        assertEquals(59_999L, position)
        repeat(20) { position = SeekStep.target(position, 60_000, 10, false)!! }
        assertEquals(0L, position)
    }
}
