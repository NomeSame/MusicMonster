package com.nomesame.musicmonster

import com.nomesame.musicmonster.ui.theme.computeInSampleSize
import org.junit.Assert.*
import org.junit.Test

class MusicBoundaryTest {
    @Test fun playlistLookupRunsOncePerEntry() {
        val calls = mutableMapOf<String, Int>()
        val (items, start) = MusicLogic.resolvePlaylist(listOf("missing", "a", "b"), "b") {
            calls[it] = (calls[it] ?: 0) + 1
            if (it == "missing") null else it.uppercase()
        }
        assertEquals(listOf("A", "B"), items)
        assertEquals(1, start)
        assertEquals(mapOf("missing" to 1, "a" to 1, "b" to 1), calls)
    }
    @Test fun playlistResolutionUsesTheValueAlreadyResolved() {
        var calls = 0
        val (items, _) = MusicLogic.resolvePlaylist(listOf("a"), "a") { if (calls++ == 0) "A" else null }
        assertEquals(listOf("A"), items)
    }
    @Test fun unicodeDigitRunsSortNumerically() {
        assertTrue(MusicLogic.compareNatural("Track ٢", "Track 10") < 0)
        assertEquals(0, MusicLogic.compareNatural("Track ٠٢", "Track 2"))
        assertEquals(0, MusicLogic.compareNatural("Track １２", "Track 12"))
    }
    @Test fun numericEqualityIsTransitiveAcrossDigitAlphabets() {
        val equal = listOf("0002", "2", "٢", "٠٢", "２", "００２")
        for (a in equal) for (b in equal) assertEquals("$a vs $b", 0, MusicLogic.compareNatural(a, b))
    }
    @Test fun audioMimeTypeIsCaseInsensitiveAndToleratesWhitespace() {
        assertTrue(MusicLogic.isAudioFile("track", "Audio/FLAC"))
        assertTrue(MusicLogic.isAudioFile("track", " audio/mpeg "))
        assertFalse(MusicLogic.isAudioFile("movie.mp4", "video/mp4"))
    }
    @Test fun samplingRoundsUpInsteadOfLettingImageExceedTarget() {
        assertEquals(4, computeInSampleSize(513, 200, 256))
        assertEquals(8, computeInSampleSize(1025, 200, 256))
    }
    @Test fun samplingMaximumDimensionCannotOverflow() {
        assertEquals(1 shl 30, computeInSampleSize(Int.MAX_VALUE, 1, 1))
    }
    @Test fun extremeDurationsRemainNonNegativeAndLocaleIndependent() {
        assertEquals("0:00", MusicLogic.formatTime(Long.MIN_VALUE))
        assertEquals("2562047788015:12:55", MusicLogic.formatTime(Long.MAX_VALUE))
    }
}
