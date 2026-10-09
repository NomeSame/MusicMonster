package com.nomesame.musicmonster

import org.junit.Assert.*
import org.junit.Test

class SongSelectionTest {
    @Test fun longPressStartsWithExactlyThatSong() {
        assertEquals(SongSelection(true, setOf("a")), SongSelection().start("a"))
    }
    @Test fun togglingDoesNotMutatePreviousState() {
        val original = SongSelection().start("a")
        val selected = original.toggle("b")
        assertEquals(setOf("a"), original.ids)
        assertEquals(setOf("a", "b"), selected.ids)
        assertEquals(setOf("b"), selected.toggle("a").ids)
    }
    @Test fun lastDeselectionKeepsTheModeOpen() {
        val state = SongSelection().start("a").toggle("a")
        assertTrue(state.active)
        assertTrue(state.ids.isEmpty())
    }
    @Test fun inactiveSelectionIgnoresCheckboxActions() {
        assertEquals(SongSelection(), SongSelection().toggle("a"))
        assertEquals(SongSelection(), SongSelection().toggleAll(listOf("a")))
    }
    @Test fun selectAllAndDeselectAllAreSymmetric() {
        val all = SongSelection().start("b").toggleAll(listOf("a", "b", "c"))
        assertTrue(all.allSelected(listOf("a", "b", "c")))
        assertTrue(all.toggleAll(listOf("a", "b", "c")).ids.isEmpty())
    }
    @Test fun emptyLibraryIsNeverAllSelected() {
        assertFalse(SongSelection(true).allSelected(emptyList()))
        assertTrue(SongSelection(true).toggleAll(emptyList()).ids.isEmpty())
    }
    @Test fun removedSongsAreDiscardedWithoutSelectingNewOnes() {
        val state = SongSelection(true, setOf("a", "b", "c")).retain(listOf("b", "d"))
        assertEquals(setOf("b"), state.ids)
        assertFalse(state.allSelected(listOf("b", "d")))
    }
    @Test fun playlistOrderFollowsLibraryAndSkipsDuplicatesAndStaleIds() {
        val state = SongSelection(true, linkedSetOf("c", "missing", "a", "b"))
        assertEquals(listOf("a", "b", "c"), state.orderedIds(listOf("a", "a", "b", "c")))
    }
    @Test fun unicodeIdsRemainDistinct() {
        val ids = listOf("😀", "ä", "A", "a", "曲")
        assertEquals(ids.toSet(), SongSelection(true).toggleAll(ids).ids)
    }
    @Test fun largeLibraryCanSelectAndClearEverything() {
        val ids = (0 until 10000).map { "song_$it" }
        val selected = SongSelection(true).toggleAll(ids)
        assertEquals(10000, selected.ids.size)
        assertEquals(ids, selected.orderedIds(ids))
        assertTrue(selected.toggleAll(ids).ids.isEmpty())
    }
}
