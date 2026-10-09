package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.PlaylistRepository
import com.nomesame.musicmonster.model.Playlist
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PlaylistOccurrencesTest {
    private lateinit var app: Application
    private lateinit var vm: MainViewModel
    private lateinit var repo: PlaylistRepository
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repo = PlaylistRepository(prefs, app.contentResolver)
        vm = MainViewModel(app)
        vm.applyLibrarySongs(listOf("a", "b").map { Song(it, it, Uri.EMPTY, 1000) })
    }
    @Test fun targetSelectionStartsEmptyAndRemembersPlaylist() {
        val playlist = vm.createPlaylist("Target", vm.songs.value.first())
        assertTrue(vm.startPlaylistSelection(playlist))
        assertEquals(SongSelection(true, emptySet(), playlist.id), vm.songSelection.state.value)
    }
    @Test fun sameSongCanBeAddedThreeTimesAndSurviveReload() {
        val playlist = vm.createPlaylist("Target", vm.songs.value.first())
        repeat(2) {
            vm.startPlaylistSelection(playlist)
            vm.songSelection.toggle("a")
            assertTrue(vm.addSelectionToPlaylist(playlist))
        }
        assertEquals(listOf("a", "a", "a"), playlist.songIds.toList())
        assertEquals(playlist.songIds.toList(), repo.load()!!.playlists.single().songIds.toList())
    }
    @Test fun targetDoesNotAllowAddingToAnotherPlaylist() {
        val first = vm.createPlaylist("First", null)
        val second = vm.createPlaylist("Second", null)
        vm.startPlaylistSelection(first)
        vm.songSelection.toggle("a")
        assertFalse(vm.addSelectionToPlaylist(second))
        assertTrue(first.songIds.isEmpty())
        assertTrue(second.songIds.isEmpty())
    }
    @Test fun cancelLeavesExistingOccurrencesUntouched() {
        val playlist = vm.createPlaylist("Target", vm.songs.value.first())
        vm.startPlaylistSelection(playlist)
        vm.songSelection.toggle("a")
        vm.songSelection.finish()
        assertEquals(listOf("a"), playlist.songIds.toList())
        assertFalse(vm.addSelectionToPlaylist(playlist))
    }
    @Test fun deletedPlaylistCannotStartOrReceiveSelection() {
        val playlist = vm.createPlaylist("Deleted", null)
        vm.playlists.remove(playlist)
        assertFalse(vm.startPlaylistSelection(playlist))
        assertFalse(vm.addSelectionToPlaylist(playlist))
    }
    @Test fun targetPlaylistAndSelectionSurviveSavedStateSerialization() {
        val handle = SavedStateHandle()
        val first = SongSelectionController(handle)
        first.forPlaylist("playlist_17")
        first.toggle("ä")
        val restored = SongSelectionController(SavedStateHandle.createHandle(handle.savedStateProvider().saveState(), null))
        assertEquals(first.state.value, restored.state.value)
        restored.finish()
        assertNull(restored.state.value.targetPlaylistId)
    }
    @Test fun importPreservesOccurrencesAndRepeatedImportIsIdempotent() {
        val target = mutableStateListOf<Playlist>()
        val raw = """[{"id":"p","name":"Mix","songs":["a","b","a","a"]}]"""
        repeat(3) { assertNotNull(repo.importInto(raw, target, 0)) }
        assertEquals(listOf("a", "b", "a", "a"), target.single().songIds.toList())
    }
    @Test fun mergeAddsOnlyOccurrenceDeficitsWithoutReorderingExistingEntries() {
        val playlist = Playlist("p", "Mix", mutableStateListOf("b", "a", "a"))
        val target = mutableStateListOf(playlist)
        val raw = """[{"id":"p","name":"Mix","songs":["a","a","a","b","b"]}]"""
        repo.importInto(raw, target, 0)
        assertEquals(listOf("b", "a", "a", "a", "b"), playlist.songIds.toList())
        repo.importInto(raw, target, 0)
        assertEquals(listOf("b", "a", "a", "a", "b"), playlist.songIds.toList())
    }
    @Test fun exportAndImportRoundTripKeepsExactRepeatedEntryOrder() {
        val playlist = Playlist("p", "Mix 😀", mutableStateListOf("a", "b", "a", "b", "a"))
        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://test/occurrences.json")
        shadowOf(app.contentResolver).registerOutputStream(uri, output)
        repo.export(uri, listOf(playlist))
        val target = mutableStateListOf<Playlist>()
        assertNotNull(repo.importInto(output.toString("UTF-8"), target, 0))
        assertEquals(playlist.songIds.toList(), target.single().songIds.toList())
    }
    @Test fun duplicateStoredPlaylistRecordsMergeCountsWithoutMultiplyingThem() {
        val raw = """[{"id":"p","name":"Mix","songs":["a","a"]},{"id":"p","name":"Mix","songs":["a","a","b"]}]"""
        app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit().putString("playlists_json", raw).commit()
        assertEquals(listOf("a", "a", "b"), repo.load()!!.playlists.single().songIds.toList())
    }

    @Test fun playbackIntentCarriesRepeatedEntriesAndTheClickedOccurrence() {
        val playlist = Playlist("p", "Mix", mutableStateListOf("a", "b", "a"))
        vm.playPlaylist(playlist, "a", 2)
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals(MusicService.ACTION_PLAY_PLAYLIST, intent.action)
        assertEquals(listOf("a", "b", "a"), intent.getStringArrayListExtra(MusicService.EXTRA_PLAYLIST_IDS))
        assertEquals("a", intent.getStringExtra(MusicService.EXTRA_PLAYLIST_START_ID))
        assertEquals(2, intent.getIntExtra(MusicService.EXTRA_PLAYLIST_START_INDEX, -1))
    }
}
