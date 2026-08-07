package com.nomesame.musicmonster

import android.net.Uri
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure-JVM tests for the framework-independent music logic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicLogicTest {

    private fun song(id: String, title: String) = Song(
        id = id,
        title = title,
        uri = Uri.parse("content://media/external/audio/media/$id"),
        durationMs = 0L
    )

    // --- compareNatural ----------------------------------------------------

    @Test
    fun naturalSortOrdersNumbersNumerically() {
        val titles = listOf("song10", "song2", "song1", "song1b")
        val sorted = titles.sortedWith { a, b -> MusicLogic.compareNatural(a, b) }
        assertEquals(listOf("song1", "song1b", "song2", "song10"), sorted)
    }

    @Test
    fun naturalSortTreatsLeadingZerosAsEqual() {
        assertEquals(0, MusicLogic.compareNatural("01", "1"))
        assertEquals(0, MusicLogic.compareNatural("track 001", "track 1"))
    }

    @Test
    fun naturalSortHandlesVeryLongDigitRunsWithoutOverflow() {
        // A 30-digit number overflows Long; the comparison must still be
        // correct (previous char-by-char Long accumulation produced wrong
        // results / overflow).
        val a = "9".repeat(30)
        val b = "1" + "0".repeat(30)
        val sorted = listOf(b, a).sortedWith { x, y -> MusicLogic.compareNatural(x, y) }
        assertEquals(listOf(a, b), sorted) // 9e29 < 1e30
    }

    @Test
    fun naturalSortIsCaseInsensitive() {
        assertEquals(0, MusicLogic.compareNatural("Hello", "hello"))
        assertEquals(0, MusicLogic.compareNatural("AB", "ab"))
    }

    @Test
    fun naturalSortIsDeterministicAcrossLocales() {
        // Regression: a non-ROOT lowercase() would sort "I" differently under
        // the Turkish locale (dotless ı vs dotted i). Sorting must be identical
        // on every device locale, otherwise the same library lists differently
        // per user region. compareNatural uses Locale.ROOT, so the order must
        // be the same under tr-TR and under en-US.
        val titles = listOf("I", "i", "INtro", "inferno", "Intro", "idea", "Info",
            "9", "10", "A song 2", "a song 10")
        val base = Locale.getDefault()
        try {
            val underTr = runInLocale(Locale.forLanguageTag("tr-TR")) {
                titles.sortedWith { a, b -> MusicLogic.compareNatural(a, b) }
            }
            val underUs = runInLocale(Locale.US) {
                titles.sortedWith { a, b -> MusicLogic.compareNatural(a, b) }
            }
            assertEquals(underUs, underTr)
        } finally {
            Locale.setDefault(base)
        }
    }

    /** Runs [block] with the JVM default locale set to [l], restoring it after. */
    private fun <T> runInLocale(l: Locale, block: () -> T): T {
        val prev = Locale.getDefault()
        Locale.setDefault(l)
        return try {
            block()
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun naturalSortHandlesEmptyAndUnicode() {
        assertEquals(0, MusicLogic.compareNatural("", ""))
        assertTrue(MusicLogic.compareNatural("", "a") < 0)
        assertTrue(MusicLogic.compareNatural("a", "") > 0)
        // Mixed-script / accented titles must not crash and keep a total order.
        val mixed = listOf("Zäpfel", "Äpfel", "Éclair", "zebra", "10", "9")
        val sorted = mixed.sortedWith { a, b -> MusicLogic.compareNatural(a, b) }
        assertEquals(mixed.size, sorted.size)
        assertEquals(mixed.toSet(), sorted.toSet())
    }

    @Test
    fun naturalSortComparesEqualDigitRunsByLengthThenValue() {
        assertTrue(MusicLogic.compareNatural("a 2", "a 10") < 0)
        assertTrue(MusicLogic.compareNatural("a 10", "a 2") > 0)
    }

    @Test
    fun sortNaturalSortsSongs() {
        val songs = listOf(song("10", "Track 10"), song("2", "Track 2"), song("1", "Track 1"))
        val sorted = MusicLogic.sortNatural(songs)
        assertEquals(listOf("Track 1", "Track 2", "Track 10"), sorted.map { it.title })
    }

    // --- nextUpSong -------------------------------------------------------

    @Test
    fun nextUpSongEmptyListReturnsNull() {
        assertNull(MusicLogic.nextUpSong(emptyList(), "anything"))
    }

    @Test
    fun nextUpSongWrapsAroundAtEnd() {
        val songs = listOf(song("1", "A"), song("2", "B"), song("3", "C"))
        assertEquals("B", MusicLogic.nextUpSong(songs, "1")?.title)
        assertEquals("C", MusicLogic.nextUpSong(songs, "2")?.title)
        assertEquals("A", MusicLogic.nextUpSong(songs, "3")?.title) // wraps
    }

    @Test
    fun nextUpSongUnknownCurrentFallsBackToFirst() {
        val songs = listOf(song("1", "A"), song("2", "B"))
        assertEquals("A", MusicLogic.nextUpSong(songs, null)?.title)
        assertEquals("A", MusicLogic.nextUpSong(songs, "does-not-exist")?.title)
    }

    @Test
    fun nextUpSongSingleSongRepeatsItself() {
        val songs = listOf(song("1", "Only"))
        assertEquals("Only", MusicLogic.nextUpSong(songs, "1")?.title)
    }

    // --- formatTime -------------------------------------------------------

    @Test
    fun formatTimeHandlesRanges() {
        assertEquals("0:00", MusicLogic.formatTime(0L))
        assertEquals("0:00", MusicLogic.formatTime(999L)) // sub-second truncates
        assertEquals("0:01", MusicLogic.formatTime(1000L))
        assertEquals("1:00", MusicLogic.formatTime(60_000L))
        assertEquals("59:59", MusicLogic.formatTime(3_599_000L))
        assertEquals("1:00:00", MusicLogic.formatTime(3_600_000L))
        assertEquals("100:00:00", MusicLogic.formatTime(360_000_000L))
    }

    @Test
    fun formatTimeClampsNegatives() {
        assertEquals("0:00", MusicLogic.formatTime(-500L))
    }

    // --- buildShuffleSeed ------------------------------------------------

    @Test
    fun shuffleSeedsAreNotAllEqual() {
        val seeds = List(100) { MusicLogic.buildShuffleSeed() }
        assertTrue("Seeds should vary", seeds.distinct().size > 1)
    }

    // --- isAudioFile -----------------------------------------------------

    @Test
    fun isAudioFileAcceptsKnownExtensionsCaseInsensitively() {
        assertTrue(MusicLogic.isAudioFile("song.MP3", null))
        assertTrue(MusicLogic.isAudioFile("song.m4a", null))
        assertTrue(MusicLogic.isAudioFile("song.flac", null))
        assertTrue(MusicLogic.isAudioFile("song.wav", null))
        assertTrue(MusicLogic.isAudioFile("song.ogg", null))
        assertTrue(MusicLogic.isAudioFile("song.mp3", null))
    }

    @Test
    fun isAudioFileAcceptsAudioMimeTypeWithoutExtension() {
        assertTrue(MusicLogic.isAudioFile("weird-name-no-ext", "audio/mpeg"))
        assertTrue(MusicLogic.isAudioFile("file", "audio/ogg"))
    }

    @Test
    fun isAudioFileRejectsNonAudio() {
        assertTrue(!MusicLogic.isAudioFile("cover.jpg", null))
        assertTrue(!MusicLogic.isAudioFile("lyrics.txt", null))
        assertTrue(!MusicLogic.isAudioFile("song.mp3.exe", null))
        assertTrue(!MusicLogic.isAudioFile("audio.txt", "text/plain"))
    }

    @Test
    fun isAudioFileExtensionWinsOverConflictingMime() {
        // By design the extension is authoritative: many providers report a
        // generic MIME type (application/octet-stream) for real audio files,
        // so a non-audio MIME must not veto an audio extension.
        assertTrue(MusicLogic.isAudioFile("song.mp3", "application/pdf"))
        assertTrue(MusicLogic.isAudioFile("track.flac", "application/octet-stream"))
    }

    @Test
    fun isAudioFileHandlesNullAndEmpty() {
        assertTrue(!MusicLogic.isAudioFile("", null))
        assertTrue(!MusicLogic.isAudioFile("", "video/mp4"))
    }

    // --- nextUpSong extra edge cases -------------------------------------

    @Test
    fun nextUpSongWithDuplicateIdsAdvancesPastFirstMatch() {
        // Two songs share the same id; "next" must return the song after the
        // *first* match (indexOfFirst semantics), not the second duplicate.
        val songs = listOf(song("dup", "A"), song("dup", "B"), song("3", "C"))
        assertEquals("B", MusicLogic.nextUpSong(songs, "dup")?.title)
    }

    @Test
    fun nextUpSongSingleSongWithUnknownIdFallsBackToFirst() {
        val songs = listOf(song("1", "Only"))
        assertEquals("Only", MusicLogic.nextUpSong(songs, "nope")?.title)
    }

    // --- formatTime extra edge cases -------------------------------------

    @Test
    fun formatTimeUsesOnlyWholeSeconds() {
        // 1.5s truncates to 1s (no rounding up)
        assertEquals("0:01", MusicLogic.formatTime(1500L))
        assertEquals("0:01", MusicLogic.formatTime(1999L))
    }

    @Test
    fun formatTimePadsMinutesAndHours() {
        assertEquals("1:01:01", MusicLogic.formatTime(3_661_000L))
        assertEquals("0:05", MusicLogic.formatTime(5_000L))
        assertEquals("12:34", MusicLogic.formatTime(754_000L))
    }

    @Test
    fun formatTimeHandlesHugeDurations() {
        // 10 days in ms — must not overflow or crash
        val tenDaysMs = 10L * 24 * 3600 * 1000
        assertEquals("240:00:00", MusicLogic.formatTime(tenDaysMs))
    }

    // --- compareNatural extra edge cases ---------------------------------

    @Test
    fun compareNaturalHandlesMixedDigitAndText() {
        assertTrue(MusicLogic.compareNatural("Track 2 version 2", "Track 2 version 10") < 0)
        assertTrue(MusicLogic.compareNatural("a1b2", "a1b10") < 0)
        assertTrue(MusicLogic.compareNatural("a1b10", "a1b2") > 0)
    }

    @Test
    fun compareNaturalHandlesSparseZeros() {
        // "0" vs "00" both collapse to empty digit runs -> equal
        assertEquals(0, MusicLogic.compareNatural("0", "00"))
        // Digit runs of all zeros collapse to empty; then "a" vs "b" decides.
        assertTrue(MusicLogic.compareNatural("000a", "00b") < 0)
        assertTrue(MusicLogic.compareNatural("000b", "00a") > 0)
    }

    @Test
    fun compareNaturalIsTotalOrderOnWhitespace() {
        val items = listOf("a b", "a  b", "a", "ab", "A b", "a\tb", "a\nb")
        val sorted = items.sortedWith { x, y -> MusicLogic.compareNatural(x, y) }
        assertEquals(items.size, sorted.size)
        assertEquals(items.toSet(), sorted.toSet())
    }

    // --- Comparator contract (the "nobody checks" invariant) ---------------
    //
    // sortedWith (TimSort) throws IllegalArgumentException if the comparator
    // isn't a consistent total order. Hundreds of real titles must not make the
    // app crash on startup. The checks below assert the raw properties TimSort
    // depends on, so any future regression to compareNatural fails a test here
    // instead of as a device-only crash.

    @Test
    fun comparatorIsAntiSymmetric() {
        val pairs = listOf(
            "ATrack" to "Atrack",
            "Song 1" to "Song 10",
            "  hello" to "hello",
            "hello " to "hello",
            "" to " ",
            "01" to "1",
            "a0002" to "a2"
        )
        for ((a, b) in pairs) {
            val ab = MusicLogic.compareNatural(a, b)
            val ba = MusicLogic.compareNatural(b, a)
            // sign(ab) == -sign(ba); and ab == 0 iff ba == 0.
            assertEquals("sign mismatch for ($a, $b)", -ab, ba)
        }
    }

    @Test
    fun comparatorIsTransitive() {
        // TimSort requires a transitive (total pre-)order. Brute-force check
        // every triple from a tricky sample set: if a <= b and b <= c then
        // a <= c. This is the property that breaks the comparator historically
        // (digit-run collapse + whitespace) and surfaces as a device-only crash
        // ("Comparison method violates its general contract!").
        val sample = listOf(
            "", " ", "  a", "a", "a ", "a  ", "A", "ab", "b", "B", "Z",
            "0", "00", "000", "01", "1", "10", "9", "9a",
            "a1", "a10", "a 2", "a 10", "2 song 1", "2 song 10", "track_001"
        )
        fun le(x: String, y: String): Boolean = MusicLogic.compareNatural(x, y) <= 0
        for (a in sample) for (b in sample) for (c in sample) {
            if (le(a, b) && le(b, c)) {
                assertTrue(
                    "transitivity violated: '$a' <= '$b' <= '$c' but '$a' > '$c'",
                    le(a, c)
                )
            }
        }
    }

    @Test
    fun comparatorIsStableUnderRepeatedSorts() {
        // The natural sorter must be a deterministic total order: sorting the
        // same (tricky) multiset over and over, in different starting orders,
        // must yield the same sequence every time — and never throw.
        //
        // Elements are chosen so every pair compares strictly (no two are
        // comparator-equal). sortedWith (TimSort) is stable: equal elements
        // keep their input order, so using distinct-in-comparator elements
        // lets us assert exact whole-list equality across every shuffle.
        val base = listOf(
            "", "A", "Z", "a 10", "a 2", "a1", "a10", "a9", "ab",
            "track_001", "1", "10", "9", "9a"
        )
        // Guard: fail loudly if the fixture is ill-constructed (comparator
        // collision between two distinct elements would weaken the test).
        for (i in base.indices) for (j in i + 1 until base.size) {
            assertTrue(
                "fixture collision: '${base[i]}' == '${base[j]}'",
                MusicLogic.compareNatural(base[i], base[j]) != 0
            )
        }
        val canonical = base.sortedWith { x, y -> MusicLogic.compareNatural(x, y) }
        repeat(20) { i ->
            val shuffled = base.shuffled(kotlin.random.Random(i.toLong()))
            val result = shuffled.sortedWith { x, y -> MusicLogic.compareNatural(x, y) }
            assertEquals("sort #$i inconsistent", canonical, result)
        }
    }
}
