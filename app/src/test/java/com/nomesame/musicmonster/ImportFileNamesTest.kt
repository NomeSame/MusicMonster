package com.nomesame.musicmonster

import com.nomesame.musicmonster.data.ImportFileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the framework-free import file-name rules. No Robolectric,
 * no Android runtime — the trust boundary around untrusted plugin names is
 * exercised directly.
 */
class ImportFileNamesTest {

    // --- candidateName -----------------------------------------------------

    @Test
    fun candidateNameKeepsTheBaseForTheFirstIndex() {
        assertEquals("Song", ImportFileNames.candidateName("Song", 1))
    }

    @Test
    fun candidateNameAppendsBracketedIndexFromTwo() {
        assertEquals("Song (2)", ImportFileNames.candidateName("Song", 2))
        assertEquals("Song (3)", ImportFileNames.candidateName("Song", 3))
        assertEquals("Song (10)", ImportFileNames.candidateName("Song", 10))
    }

    @Test
    fun candidateNameNeverDoubleResolvesAnAlreadySuffixedName() {
        // Widens only forward: "a (2)" + 3 is "a (2) (3)", never "a (5)".
        assertEquals("a (2) (3)", ImportFileNames.candidateName("a (2)", 3))
        assertEquals("a (2) (2)", ImportFileNames.candidateName("a (2)", 2))
    }

    // --- splitExtension ----------------------------------------------------

    @Test
    fun splitExtensionSeparatesStemAndKnownExtension() {
        assertEquals("Song" to ".mp3", ImportFileNames.splitExtension("Song.mp3"))
        assertEquals("my.song" to ".flac", ImportFileNames.splitExtension("my.song.flac"))
    }

    @Test
    fun splitExtensionWithoutExtensionOrForHiddenNames() {
        assertEquals("Song" to "", ImportFileNames.splitExtension("Song"))
        assertEquals("a (2) (3)" to "", ImportFileNames.splitExtension("a (2) (3)"))
        assertEquals(".hidden" to "", ImportFileNames.splitExtension(".hidden"))
    }

    // --- sanitizeName ------------------------------------------------------

    @Test
    fun sanitizeKeepsOrdinaryAndUnicodeNames() {
        assertEquals("Song", ImportFileNames.sanitizeName("Song"))
        assertEquals("Bärenlied", ImportFileNames.sanitizeName("Bärenlied"))
    }

    @Test
    fun sanitizePreservesEmoji() {
        assertEquals("Song 🎵", ImportFileNames.sanitizeName("Song 🎵"))
    }

    @Test
    fun sanitizeRemovesPathSeparatorsAndInvalidCharacters() {
        val cleaned = ImportFileNames.sanitizeName("a/b\\c:d*e?f\"g<h>i|j")
        assertEquals("abcdefghij", cleaned)
        assertFalse(cleaned.contains('/'))
        assertFalse(cleaned.contains('\\'))
    }

    @Test
    fun sanitizeStripsControlCharacters() {
        assertEquals("Linebreak", ImportFileNames.sanitizeName("Line\u0000break\u0001\u001F"))
    }

    @Test
    fun sanitizeTrimsLeadingAndTrailingDotsAndSpaces() {
        assertEquals("hidden", ImportFileNames.sanitizeName("  .hidden..  "))
        assertEquals("Song", ImportFileNames.sanitizeName("...Song..."))
    }

    @Test
    fun sanitizeFallsBackForEmptyAndSeparatorOnlyNames() {
        assertEquals(ImportFileNames.FALLBACK_BASE_NAME, ImportFileNames.sanitizeName(""))
        assertEquals(ImportFileNames.FALLBACK_BASE_NAME, ImportFileNames.sanitizeName("   "))
        assertEquals(ImportFileNames.FALLBACK_BASE_NAME, ImportFileNames.sanitizeName("..."))
        assertEquals(ImportFileNames.FALLBACK_BASE_NAME, ImportFileNames.sanitizeName("/\\:*"))
    }

    @Test
    fun sanitizeCapsLengthAndKeepsExtension() {
        val long = "a".repeat(500) + ".mp3"
        val cleaned = ImportFileNames.sanitizeName(long)
        assertEquals(ImportFileNames.MAX_NAME_LENGTH, cleaned.length)
        assertTrue(cleaned.endsWith(".mp3"))
    }

    @Test
    fun sanitizeOfOverlongStemWithoutExtensionIsAlsoCapped() {
        val cleaned = ImportFileNames.sanitizeName("b".repeat(500))
        assertEquals(ImportFileNames.MAX_NAME_LENGTH, cleaned.length)
    }

    @Test
    fun sanitizeNeverProducesABlankOrSeparatorBearingName() {
        listOf("", "   ", "...", "///", "\u0000", " a/b ").forEach {
            val cleaned = ImportFileNames.sanitizeName(it)
            assertTrue("blank name from \"$it\"", cleaned.isNotBlank())
            assertFalse("separator leaked from \"$it\"", cleaned.contains('/'))
            assertFalse("backslash leaked from \"$it\"", cleaned.contains('\\'))
        }
    }
}
