package com.nomesame.musicmonster

import android.net.Uri
import com.nomesame.musicmonster.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LibraryOrganizationTest {
    private val root = LibraryFolder("content://test/root", null, "Music", listOf("Music"))
    private val band = LibraryFolder("content://test/band", root.id, "Heilung", listOf("Music", "Heilung"))
    private val empty = LibraryFolder("content://test/empty", band.id, "Empty", band.path + "Empty")
    private fun song(id: String, title: String, name: String, folder: LibraryFolder = root, mime: String? = null) =
        Song(id, title, Uri.parse("content://test/$id"), 1000L, name, Uri.parse(folder.id), mime, folder.path)
    private val songs get() = listOf(song("a", "Track 10", "a.mp3"), song("v", "Track 2", "v.MP4", band),
        song("b", "Track 2", "b.flac", band), song("w", "Track 10", "w.webm"))
    private val catalog get() = LibraryCatalog(songs, listOf(root, band, empty), root.id)

    @Test fun presetsPartitionAndNaturallySortWithoutMutatingCatalogue() {
        val c = catalog
        fun ids(p: LibraryPreset) = LibraryOrganization.present(c, p, false, null).songs.map { it.id }
        assertEquals(listOf("v", "w", "b", "a"), ids(LibraryPreset.VIDEOS_FIRST))
        assertEquals(listOf("b", "a", "v", "w"), ids(LibraryPreset.AUDIO_FIRST))
        assertEquals(listOf("b", "v", "a", "w"), ids(LibraryPreset.TITLE))
        assertEquals(listOf("v", "w"), ids(LibraryPreset.VIDEOS))
        assertEquals(listOf("b", "a"), ids(LibraryPreset.AUDIO))
        assertEquals(listOf("a", "v", "b", "w"), c.songs.map { it.id })
    }
    @Test fun folderViewContainsDirectChildrenAndFilesAndKeepsEmptyFolders() {
        val rootView = LibraryOrganization.present(catalog, LibraryPreset.TITLE, true, null)
        assertEquals(listOf("a", "w"), rootView.songs.map { it.id })
        assertEquals(listOf(band), rootView.folders)
        assertFalse(rootView.canGoBack)
        val bandView = LibraryOrganization.present(catalog, LibraryPreset.VIDEOS, true, band.id)
        assertEquals(listOf("v"), bandView.songs.map { it.id })
        assertEquals(listOf(empty), bandView.folders)
        assertTrue(bandView.canGoBack)
        val emptyView = LibraryOrganization.present(catalog, LibraryPreset.AUDIO, true, empty.id)
        assertTrue(emptyView.songs.isEmpty())
        assertTrue(emptyView.folders.isEmpty())
        assertTrue(emptyView.canGoBack)
    }
    @Test fun staleFolderFallsBackToRootAndMediaStoreCannotBrowse() {
        assertEquals(root, LibraryOrganization.present(catalog, LibraryPreset.TITLE, true, "missing").folder)
        val view = LibraryOrganization.present(LibraryCatalog(songs), LibraryPreset.TITLE, true, band.id)
        assertFalse(view.browsing)
        assertFalse(view.canBrowse)
        assertEquals(4, view.songs.size)
    }
    @Test fun mimeTypeTakesPrecedenceAndVideoExtensionsIgnoreLocaleAndCase() {
        assertFalse(LibraryOrganization.isVideo("audio.mp4", " audio/mp4 "))
        assertTrue(LibraryOrganization.isVideo("opaque.bin", " VIDEO/MP4 "))
        for (name in listOf("a.MP4", "a.MKV", "a.WebM", "a.MOV")) assertTrue(LibraryOrganization.isVideo(name, null))
        for (name in listOf("a.mp3", "a.m4a", "a.wav", "a.flac")) assertFalse(LibraryOrganization.isVideo(name, null))
        assertFalse(LibraryOrganization.isVideo(null, null))
        assertTrue(LibraryOrganization.accepts("opaque", "video/webm"))
        assertFalse(LibraryOrganization.accepts("notes.txt", "text/plain"))
    }
    @Test fun completeCycleAndStableTieBreaksWithUnicodeDuplicateTitles() {
        var p = LibraryPreset.VIDEOS_FIRST
        repeat(5) { p = p.next() }
        assertEquals(LibraryPreset.VIDEOS_FIRST, p)
        val duplicate = listOf(song("z", "音", "a.mp3"), song("a", "音", "a.mp3", band))
        assertEquals(listOf("a", "z"), LibraryOrganization.present(LibraryCatalog(duplicate), LibraryPreset.TITLE, false, null).songs.map { it.id })
        assertTrue(LibraryOrganization.present(LibraryCatalog(), p, false, null).songs.isEmpty())
    }
}
