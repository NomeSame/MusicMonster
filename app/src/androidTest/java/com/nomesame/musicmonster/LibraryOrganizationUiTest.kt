package com.nomesame.musicmonster

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.model.*
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.screens.PlayerScreen
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.*
import org.junit.Assert.*

class LibraryOrganizationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: ComponentActivity
    private lateinit var vm: MainViewModel
    private lateinit var prefs: SharedPreferences
    private val store = ViewModelStore()
    private val root = LibraryFolder("content://owned/root", null, "Music", listOf("Music"))
    private val band = LibraryFolder("content://owned/band", root.id, "Heilung", listOf("Music", "Heilung"))
    private val empty = LibraryFolder("content://owned/empty", band.id, "Empty", band.path + "Empty")
    private val songs = listOf(
        Song("audio", "Audio", Uri.EMPTY, 1000, "Audio.mp3", Uri.parse(root.id), "audio/mpeg", root.path),
        Song("video", "Video", Uri.EMPTY, 1000, "Video.mp4", Uri.parse(band.id), "video/mp4", band.path))
    @Before fun setup() {
        val context = inst.targetContext
        prefs = context.getSharedPreferences("library-owned-ui", 0)
        prefs.edit().clear().commit()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        inst.runOnMainSync {
            vm = MainViewModel(app); store.put("fixture", vm)
            vm.applyLibrarySongs(songs)
            vm.library.update(LibraryCatalog(songs, listOf(root, band, empty), root.id))
        }
        host = launchComposeHost()
        inst.runOnMainSync {
            host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    AppLanguageContent(AppLanguage.GERMAN) {
                        MyApplicationTheme(accent = Color.Yellow) { PlayerScreen(vm, {}, {}, {}, {}, {}) }
                    }
                }
            }
        }
    }
    @After fun cleanup() {
        inst.runOnMainSync { if (::host.isInitialized) host.finish(); store.clear() }
        prefs.edit().clear().commit()
    }
    @Test fun foldersBackAndAllTitlesKeepTheCatalogueAndShowPaths() {
        compose.onNodeWithTag("song_row_video").assertExists()
        compose.onNodeWithText("Music / Heilung", substring = true).assertExists()
        compose.onNodeWithTag("library_folders").performClick()
        compose.onNodeWithTag("song_row_video").assertDoesNotExist()
        compose.onNodeWithTag("library_folder_${band.id}").performClick()
        compose.onNodeWithTag("song_row_video").assertExists()
        compose.onNodeWithTag("library_back").assertExists()
        compose.onNodeWithTag("library_folder_${empty.id}").performClick()
        compose.onNodeWithTag("library_empty").assertExists()
        compose.onNodeWithTag("library_back").performClick()
        compose.runOnIdle { assertEquals(band, vm.library.view.value.folder) }
        inst.runOnMainSync { host.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(root, vm.library.view.value.folder) }
        compose.onNodeWithTag("library_all").performClick()
        compose.onNodeWithTag("song_row_video").assertExists()
        compose.onNodeWithTag("song_row_audio").assertExists()
        compose.runOnIdle { assertEquals(songs, vm.songs.value) }
    }
    @Test fun cycleFiltersThenMenuCanRestoreAllAndSelectionIsScopedToVisibleSongs() {
        fun top(id: String) = compose.onNodeWithTag("song_row_$id").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("video") < top("audio"))
        compose.onNodeWithTag("library_preset").performClick()
        assertTrue(top("audio") < top("video"))
        repeat(2) { compose.onNodeWithTag("library_preset").performClick() }
        compose.onNodeWithTag("song_row_audio").assertDoesNotExist()
        compose.onNodeWithTag("song_row_video").assertExists()
        compose.onNodeWithTag("library_menu").performClick()
        compose.onAllNodesWithText("Alle Titel").onLast().performClick()
        compose.onNodeWithTag("song_row_audio").assertExists()
        compose.onNodeWithTag("library_folders").performClick()
        compose.onNodeWithTag("song_row_audio").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(setOf("audio"), vm.songSelection.state.value.ids)
            assertEquals(songs, vm.songs.value)
        }
        compose.onNodeWithTag("library_preset").assertDoesNotExist()
    }
}
