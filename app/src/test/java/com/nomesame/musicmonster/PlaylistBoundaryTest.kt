package com.nomesame.musicmonster

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.PlaylistRepository
import com.nomesame.musicmonster.model.Playlist
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PlaylistBoundaryTest {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repo: PlaylistRepository
    private fun playlist(id: String, name: String, vararg songs: String) =
        Playlist(id, name, mutableStateListOf(*songs))
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("playlist_boundaries", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repo = PlaylistRepository(prefs, context.contentResolver)
    }
    private fun load(raw: String): PlaylistRepository.Loaded {
        prefs.edit().putString("playlists_json", raw).commit()
        return requireNotNull(repo.load())
    }
    @Test fun malformedNeighborsDoNotDiscardValidStoredPlaylists() {
        val loaded = load("""[{"id":"a","name":"A"},null,17,{"id":"b","name":"B"}]""")
        assertEquals(listOf("a", "b"), loaded.playlists.map { it.id })
    }
    @Test fun malformedNeighborsDoNotAbortImportAfterMutatingTarget() {
        val target = mutableStateListOf(playlist("original", "Original", "old"))
        assertNotNull(repo.importInto("""[{"id":"original","name":"Original","songs":["new"]},null,{"id":"b","name":"B"}]""", target, 0))
        assertEquals(listOf("original", "b"), target.map { it.id })
        assertEquals(listOf("old", "new"), target[0].songIds.toList())
    }
    @Test fun nonStringIdentityFieldsAreNotCoercedIntoPlaylists() {
        val loaded = load("""[{"id":42,"name":"Bad"},{"id":"bad","name":null},{"id":"ok","name":"Valid"}]""")
        assertEquals(listOf("ok"), loaded.playlists.map { it.id })
    }
    @Test fun invalidSongIdsAreIgnoredAndIntentionalDuplicatesPreserved() {
        val loaded = load("""[{"id":"a","name":"A","songs":[null,7,{},""," ","s","s","🎵"]}]""")
        assertEquals(listOf("s", "s", "🎵"), loaded.playlists.single().songIds.toList())
    }
    @Test fun duplicateStoredPlaylistIdsMergeWithoutLosingSongs() {
        val loaded = load("""[{"id":"a","name":"First","songs":["x"]},{"id":"a","name":"Second","songs":["x","y"]}]""")
        assertEquals(1, loaded.playlists.size)
        assertEquals("First", loaded.playlists.single().name)
        assertEquals(listOf("x", "y"), loaded.playlists.single().songIds.toList())
    }
    @Test fun allocatedIdsAvoidExplicitIdsEarlierInSameImport() {
        val target = mutableStateListOf<Playlist>()
        repo.importInto("""[{"id":"playlist_0","name":"Explicit"},{"name":"Generated"}]""", target, 0)
        assertEquals(2, target.map { it.id }.toSet().size)
    }
    @Test fun allocatedIdsAvoidExplicitIdsLaterInSameImport() {
        val target = mutableStateListOf<Playlist>()
        repo.importInto("""[{"name":"Generated"},{"id":"playlist_0","name":"Explicit"}]""", target, 0)
        assertEquals(2, target.size)
        assertEquals(2, target.map { it.id }.toSet().size)
    }
    @Test fun staleSequenceCannotAllocateAnExistingId() {
        val target = mutableStateListOf(playlist("playlist_0", "Existing"))
        repo.importInto("""[{"name":"New"}]""", target, 0)
        assertEquals(2, target.map { it.id }.toSet().size)
    }
    @Test fun largestIntegerSuffixDoesNotOverflowSequence() {
        val loaded = load("""[{"id":"playlist_2147483647","name":"Max"},{"id":"playlist_0","name":"Zero"}]""")
        assertTrue(loaded.nextSequence >= 0)
        assertFalse(loaded.playlists.any { it.id == "playlist_${loaded.nextSequence}" })
    }
    @Test fun arbitraryNumericIdsDoNotAffectGeneratedIdSequence() {
        assertEquals(0, load("""[{"id":"999","name":"External"}]""").nextSequence)
    }
    @Test fun idMatchTakesPriorityOverEarlierNameMatch() {
        val target = mutableStateListOf(playlist("a", "Same", "a0"), playlist("b", "Other", "b0"))
        repo.importInto("""[{"id":"b","name":"Same","songs":["new"]}]""", target, 0)
        assertEquals(listOf("a0"), target[0].songIds.toList())
        assertEquals(listOf("b0", "new"), target[1].songIds.toList())
    }
    @Test fun invalidJsonLeavesTargetUntouched() {
        val original = playlist("a", "A", "x")
        val target = mutableStateListOf(original)
        assertNull(repo.importInto("""[{"id":"a","name":"A","songs":["y"]},""", target, 0))
        assertSame(original, target.single())
        assertEquals(listOf("x"), original.songIds.toList())
    }
    @Test fun repeatedUnicodeImportIsIdempotent() {
        val target = mutableStateListOf<Playlist>()
        val raw = """[{"name":"音楽 🎵","songs":["α","β","α"]}]"""
        val next = requireNotNull(repo.importInto(raw, target, 0))
        repo.importInto(raw, target, next)
        assertEquals(1, target.size)
        assertEquals(listOf("α", "β", "α"), target.single().songIds.toList())
    }
}
