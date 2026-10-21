package com.nomesame.musicmonster

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.LibrarySettingsRepository
import com.nomesame.musicmonster.model.*
import com.nomesame.musicmonster.state.LibraryController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LibraryControllerTest {
    private val root = LibraryFolder("root", null, "Music", listOf("Music"))
    private val band = LibraryFolder("band", "root", "Heilung", listOf("Music", "Heilung"))
    private val catalog = LibraryCatalog(folders = listOf(root, band), rootId = "root")
    private lateinit var settings: LibrarySettingsRepository
    @Before fun setup() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("library_test", 0)
        prefs.edit().clear().commit()
        settings = LibrarySettingsRepository(prefs)
    }
    @Test fun durationUpdatesKeepFolderPresetAndDisplayOrderWithoutRewritingSettings() {
        val c = LibraryController(settings, SavedStateHandle())
        val audio = Song("audio", "A", android.net.Uri.parse("content://owned/a"), 0,
            "A.mp3", android.net.Uri.parse(band.id), "audio/mpeg", band.path)
        val video = audio.copy(id = "video", title = "V", fileName = "V.mp4", mimeType = "video/mp4")
        val initial = catalog.copy(songs = listOf(audio, video))
        c.update(initial); c.toggleFolders(); c.open(band.id)
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("library_test", 0)
        var writes = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            c.update(initial.copy(songs = initial.songs.map { it.copy(durationMs = 8000) }))
            assertEquals(listOf("video", "audio"), c.view.value.songs.map { it.id })
            assertTrue(c.view.value.songs.all { it.durationMs == 8000L })
            assertEquals(band, c.view.value.folder)
            assertEquals(LibraryPreset.VIDEOS_FIRST, c.view.value.preset)
            assertEquals(0, writes)
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Test fun navigationBackAllAndPresetSurviveRecreation() {
        val saved = SavedStateHandle()
        val c = LibraryController(settings, saved)
        c.update(catalog)
        assertFalse(c.view.value.browsing)
        assertEquals(LibraryPreset.VIDEOS_FIRST, c.view.value.preset)
        c.toggleFolders(); c.open("band"); c.setPreset(LibraryPreset.AUDIO_FIRST)
        val restored = LibraryController(settings, SavedStateHandle(mapOf("library_root" to saved.get<String>("library_root"), "library_folder" to saved.get<String>("library_folder"))))
        restored.update(catalog)
        assertEquals(band, restored.view.value.folder)
        assertEquals(LibraryPreset.AUDIO_FIRST, restored.view.value.preset)
        restored.back(); assertEquals(root, restored.view.value.folder)
        restored.back(); assertEquals(root, restored.view.value.folder)
        restored.setPreset(LibraryPreset.VIDEOS); restored.showAll()
        assertFalse(restored.view.value.browsing)
        assertEquals(LibraryPreset.VIDEOS_FIRST, restored.view.value.preset)
    }
    @Test fun cannotOpenForeignFolderAndReloadOrRootChangeCannotLeaveStalePath() {
        val c = LibraryController(settings, SavedStateHandle())
        c.update(catalog); c.toggleFolders(); c.open("forged")
        assertEquals(root, c.view.value.folder)
        c.open("band"); c.update(catalog.copy(folders = listOf(root)))
        assertEquals(root, c.view.value.folder)
        val other = LibraryFolder("other", null, "New", listOf("New"))
        c.update(LibraryCatalog(folders = listOf(other), rootId = other.id))
        assertEquals(other, c.view.value.folder)
    }
    @Test fun corruptSettingsUseDefaultsAndFreshLaunchStartsAtRoot() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("library_test", 0)
        prefs.edit().putInt("library_preset", 7).putString("library_browse_folders", "bad").commit()
        assertEquals(LibraryPreset.VIDEOS_FIRST, settings.preset())
        assertFalse(settings.browsing())
        val c = LibraryController(settings, SavedStateHandle())
        c.update(catalog); c.toggleFolders(); c.open("band"); c.nextPreset()
        val fresh = LibraryController(settings, SavedStateHandle())
        fresh.update(catalog)
        assertEquals(root, fresh.view.value.folder)
        assertEquals(LibraryPreset.AUDIO_FIRST, fresh.view.value.preset)
    }
}
