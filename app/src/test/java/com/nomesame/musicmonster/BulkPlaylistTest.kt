package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class BulkPlaylistTest {
    private lateinit var app: Application
    private lateinit var vm: MainViewModel

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        vm = MainViewModel(app)
        vm.applyLibrarySongs(listOf("a", "b", "c").map { Song(it, it, Uri.EMPTY, 1000L) })
    }
    @Test fun createSelectedSongsOnceInLibraryOrderAndPersist() {
        vm.songSelection.start("c")
        vm.songSelection.toggle("a")
        assertTrue(vm.createPlaylistFromSelection("  My mix 😀  "))
        assertEquals("My mix 😀", vm.playlists.single().name)
        assertEquals(listOf("a", "c"), vm.playlists.single().songIds.toList())
        assertFalse(vm.songSelection.state.value.active)
        val restored = MainViewModel(app)
        restored.loadPlaylists()
        assertEquals(listOf("a", "c"), restored.playlists.single().songIds.toList())
    }
    @Test fun addPreservesExistingOrderAndAppendsSelectedOccurrences() {
        val playlist = vm.createPlaylist("Mix", vm.songs.value[1])
        vm.songSelection.start("c")
        vm.songSelection.toggleAll(listOf("a", "b", "c"))
        assertTrue(vm.addSelectionToPlaylist(playlist))
        // Re-selecting an existing song now intentionally adds another occurrence.
        assertEquals(listOf("b", "a", "b", "c"), playlist.songIds.toList())
        val restored = MainViewModel(app)
        restored.loadPlaylists()
        assertEquals(playlist.songIds.toList(), restored.playlists.single().songIds.toList())
    }
    @Test fun reAddingExistingSongsAddsAnotherOccurrence() {
        val playlist = vm.createPlaylist("Mix", vm.songs.value.first())
        vm.songSelection.start("a")
        assertTrue(vm.addSelectionToPlaylist(playlist))
        assertEquals(listOf("a", "a"), playlist.songIds.toList())
    }
    @Test fun emptySelectionAndBlankNameDoNotCreateAnything() {
        assertFalse(vm.createPlaylistFromSelection("Name"))
        vm.songSelection.start("a")
        assertFalse(vm.createPlaylistFromSelection(" \t\n"))
        assertTrue(vm.playlists.isEmpty())
        assertTrue(vm.songSelection.state.value.active)
    }
    @Test fun deletedPlaylistCannotReceiveSelection() {
        val playlist = vm.createPlaylist("Gone", null)
        vm.playlists.remove(playlist)
        vm.songSelection.start("a")
        assertFalse(vm.addSelectionToPlaylist(playlist))
        assertTrue(vm.songSelection.state.value.active)
    }
    @Test fun reloadingLibraryDropsMissingIdsAndKeepsSelectionMode() {
        vm.songSelection.start("a")
        vm.applyLibrarySongs(listOf(vm.songs.value[1]))
        assertTrue(vm.songSelection.state.value.active)
        assertTrue(vm.songSelection.state.value.ids.isEmpty())
        assertFalse(vm.createPlaylistFromSelection("Empty"))
    }
    @Test fun selectionSurvivesSavedStateSerializationAndExitClearsIt() {
        val handle = SavedStateHandle()
        val first = SongSelectionController(handle)
        first.start("ä")
        first.toggle("😀")
        val saved = handle.savedStateProvider().saveState()
        val restoredHandle = SavedStateHandle.createHandle(saved, null)
        val restored = SongSelectionController(restoredHandle)
        assertEquals(first.state.value, restored.state.value)
        restored.finish()
        val exited = SavedStateHandle.createHandle(restoredHandle.savedStateProvider().saveState(), null)
        assertEquals(SongSelection(), SongSelectionController(exited).state.value)
    }
    @Test fun singleSongPlaylistCreationStillWorks() {
        assertEquals(listOf("b"), vm.createPlaylist("Single", vm.songs.value[1]).songIds.toList())
        assertTrue(vm.createPlaylist("Empty", null).songIds.isEmpty())
    }
}
