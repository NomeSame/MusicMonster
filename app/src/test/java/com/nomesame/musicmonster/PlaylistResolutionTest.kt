package com.nomesame.musicmonster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permanent regression tests for [MusicLogic.resolvePlaylist].
 *
 * The bug these pin down: MusicService resolved playlist items and their start
 * index against two different lists. Every test whose playlist fully resolves
 * passes either way — the failure needs a playlist entry that is *not* in the
 * library, which is the normal state after a song is deleted or the library is
 * rescanned. The stale index then points past the end of the shorter resolved
 * list and ExoPlayer throws IllegalSeekPositionException.
 */
class PlaylistResolutionTest {

    private val library = mapOf(
        "a" to "Song A",
        "b" to "Song B",
        "c" to "Song C",
    )

    private fun resolve(ids: List<String>, startId: String?) =
        MusicLogic.resolvePlaylist(ids, startId) { library[it] }

    @Test
    fun resolvesEveryIdWhenTheLibraryIsComplete() {
        val (items, start) = resolve(listOf("a", "b", "c"), "b")
        assertEquals(listOf("Song A", "Song B", "Song C"), items)
        assertEquals(1, start)
    }

    @Test
    fun startIndexStaysInsideTheResolvedListWhenEntriesAreMissing() {
        // "x" and "y" are gone from the library. The start song "c" is at
        // index 4 of the playlist but only index 1 of what can actually play.
        val (items, start) = resolve(listOf("a", "x", "y", "z", "c"), "c")
        assertEquals(listOf("Song A", "Song C"), items)
        assertEquals(1, start)
        assertTrue("start index must address the resolved list", start < items.size)
    }

    @Test
    fun everyStartIdIsAddressableForAnySubsetOfMissingSongs() {
        // Brute force over all subsets: the invariant other code relies on is
        // "0 <= start < items.size whenever items is non-empty" — no arrangement
        // of missing songs may break it.
        val ids = listOf("a", "gone1", "b", "gone2", "c")
        for (startId in ids + listOf(null, "not-in-playlist")) {
            val (items, start) = resolve(ids, startId)
            assertTrue(
                "startId=$startId produced out-of-range index $start for ${items.size} items",
                items.isEmpty() || start in items.indices
            )
        }
    }

    @Test
    fun startsAtTheTopWhenTheStartSongItselfIsGone() {
        val (items, start) = resolve(listOf("a", "b"), "deleted")
        assertEquals(listOf("Song A", "Song B"), items)
        assertEquals(0, start)
    }

    @Test
    fun nullStartIdStartsAtTheTop() {
        val (items, start) = resolve(listOf("b", "c"), null)
        assertEquals(listOf("Song B", "Song C"), items)
        assertEquals(0, start)
    }

    @Test
    fun playlistOfOnlyMissingSongsResolvesToNothing() {
        val (items, start) = resolve(listOf("x", "y"), "x")
        assertTrue(items.isEmpty())
        assertEquals(0, start)
    }

    @Test
    fun emptyPlaylistIsHandled() {
        val (items, start) = resolve(emptyList(), "a")
        assertTrue(items.isEmpty())
        assertEquals(0, start)
    }

    @Test
    fun duplicateIdsKeepTheirDuplicatesAndStartAtTheFirst() {
        // A song added to a playlist twice must not collapse: the queue length
        // the user sees has to match what actually plays.
        val (items, start) = resolve(listOf("a", "b", "a"), "a")
        assertEquals(listOf("Song A", "Song B", "Song A"), items)
        assertEquals(0, start)
    }
}
