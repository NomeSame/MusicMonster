package com.nomesame.musicmonster

import com.nomesame.musicmonster.extensions.ExtensionNames
import org.junit.Assert.*
import org.junit.Test

class ExtensionNamesTest {
    @Test fun acceptsAudioUnicodeAndPreservesMetadata() {
        listOf("Music.mp3", "音楽.wav", "Überall.FLAC", "x.opus").forEach { assertTrue(it, ExtensionNames.valid(it)) }
    }
    @Test fun rejectsPathsControlsEmptyAndNonAudioResults() {
        listOf("", ".mp3", "../x.mp3", "a/b.mp3", "a\\b.mp3", "x\u0000.mp3", " x.mp3", "x.mp3 ", "x.txt",
            "x".repeat(181) + ".mp3").forEach { assertFalse(it, ExtensionNames.valid(it)) }
    }
    @Test fun choosesCollisionNameWithoutOverwritingCaseVariants() {
        assertEquals("Song (2).mp3", ExtensionNames.available("Song.mp3", setOf("song.MP3", "Song (1).mp3")))
        assertEquals("New.wav", ExtensionNames.available("New.wav", emptySet()))
    }
    @Test fun mp4IsPlayableAndFinalizesWithoutLosingItsExtension() {
        assertTrue(MusicLogic.isAudioFile("Clip.MP4", "video/mp4"))
        assertTrue(ExtensionNames.valid("Clip.mp4"))
        assertEquals("Clip (1).mp4", ExtensionNames.available("Clip.mp4", setOf("clip.MP4")))
        assertFalse(ExtensionNames.valid("../Clip.mp4"))
    }
}
