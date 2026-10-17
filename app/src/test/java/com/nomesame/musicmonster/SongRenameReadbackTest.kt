package com.nomesame.musicmonster

import org.junit.Assert.*
import org.junit.Test

class SongRenameReadbackTest {
    @Test fun staleOldMetadataCannotBecomeTheSuccessfulRenamedSong() {
        val values = ArrayDeque(listOf("old.wav", "old.wav", "new.wav"))
        var pauses = 0
        assertEquals("new.wav", SongRenameReadback.changedName("old.wav", { values.removeFirst() }, { pauses++ }))
        assertEquals(2, pauses)
    }
    @Test fun temporarilyMissingMetadataIsRetriedButNeverInvented() {
        var reads = 0
        assertEquals("new.wav", SongRenameReadback.changedName("old.wav", { if (++reads == 3) "new.wav" else null }, {}))
        assertEquals(3, reads)
        assertNull(SongRenameReadback.changedName("old.wav", { null }, {}))
    }
    @Test fun unchangedMetadataStopsAfterBoundedRetriesWithoutFalseSuccess() {
        var reads = 0; var pauses = 0
        assertNull(SongRenameReadback.changedName("old.wav", { reads++; "old.wav" }, { pauses++ }))
        assertEquals(80, reads); assertEquals(79, pauses)
    }
}
