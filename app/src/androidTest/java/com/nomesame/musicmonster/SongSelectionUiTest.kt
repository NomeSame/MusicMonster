package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.ui.screens.PlayerScreen
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private lateinit var vm: MainViewModel
    private lateinit var prefs: SharedPreferences
    private val store = ViewModelStore()

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences("song_selection_ui_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // Route test writes to an isolated preference file, never the user's playlists.
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = MainViewModel(app)
            store.put("fixture", vm)
            vm.applyLibrarySongs(listOf("a", "b", "c").map { Song(it, "Song $it", Uri.EMPTY, 1000) })
        }
        host = launchComposeHost()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent {
                MyApplicationTheme(accent = Color(0xFFE58B3C)) {
                    PlayerScreen(vm, {}, {}, {}, {}, {})
                }
            }
        }
    }
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (::host.isInitialized) host.finish()
            store.clear()
        }
        prefs.edit().clear().commit()
    }
    private fun enter() {
        compose.onNodeWithTag("song_row_a").performTouchInput { longClick() }
        compose.waitForIdle()
    }
    @Test fun longPressReplacesFolderPickerAndTapsSelectWithoutPlaying() {
        enter()
        compose.onNodeWithTag("folder_picker").assertDoesNotExist()
        compose.onNodeWithTag("song_row_a").assertIsOn()
        compose.onNodeWithTag("song_row_b").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(setOf("a", "b"), vm.songSelection.state.value.ids)
            assertFalse(vm.playbackConnection.isPlaying.value)
        }
    }
    @Test fun selectAllDeselectAllAndExitRestoreNormalList() {
        enter()
        compose.onNodeWithTag("selection_all").performClick()
        listOf("a", "b", "c").forEach { compose.onNodeWithTag("song_row_$it").assertIsOn() }
        compose.onNodeWithTag("selection_all").performClick()
        listOf("a", "b", "c").forEach { compose.onNodeWithTag("song_row_$it").assertIsOff() }
        compose.onNodeWithTag("selection_create").assertIsNotEnabled()
        compose.onNodeWithTag("selection_close").performClick()
        compose.onNodeWithTag("song_selection_bar").assertDoesNotExist()
        compose.onNodeWithTag("folder_picker").assertExists()
    }
    @Test fun missingPlaylistsDisableAddAndCreatingUsesAllSelectedSongs() {
        enter()
        compose.onNodeWithTag("selection_add").assertIsNotEnabled()
        compose.onNodeWithTag("song_row_c").performClick()
        compose.onNodeWithTag("selection_create").performClick()
        compose.onNodeWithTag("playlist_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_name").performTextInput("My mix")
        compose.onNodeWithTag("playlist_confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf("a", "c"), vm.playlists.single().songIds.toList())
            assertEquals("My mix", vm.playlists.single().name)
        }
        compose.onNodeWithTag("folder_picker").assertExists()
    }
    @Test fun existingPlaylistReceivesAllSelectedOccurrences() {
        compose.runOnIdle { vm.createPlaylist("Destination", vm.songs.value.first()) }
        enter()
        compose.onNodeWithTag("selection_all").performClick()
        compose.onNodeWithTag("selection_add").assertIsEnabled().performClick()
        compose.onNodeWithText("Destination").performClick()
        compose.runOnIdle { assertEquals(listOf("a", "a", "b", "c"), vm.playlists.single().songIds.toList()) }
        compose.onNodeWithTag("folder_picker").assertExists()
    }
    @Test fun dismissingNameDialogDoesNotCreateOrLoseSelection() {
        enter()
        compose.onNodeWithTag("selection_create").performClick()
        val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cancel)
        compose.onNodeWithText(cancel).performClick()
        compose.runOnIdle {
            assertTrue(vm.playlists.isEmpty())
            assertEquals(setOf("a"), vm.songSelection.state.value.ids)
        }
        compose.onNodeWithTag("song_selection_bar").assertExists()
    }

    private fun openPlaylistSelection() {
        compose.runOnIdle { vm.createPlaylist("Target", vm.songs.value.first()) }
        compose.onNodeWithTag("player_expand").performClick()
        compose.onNodeWithTag("panel_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("playlist_row_playlist_0").performClick()
        compose.onNodeWithTag("playlist_add_songs").performClick()
    }

    @Test fun addSongsRedirectsToSameSelectionAndMarksAlreadyPresentSongs() {
        openPlaylistSelection()
        compose.onNodeWithTag("song_selection_bar").assertExists()
        compose.onNodeWithTag("folder_picker").assertDoesNotExist()
        compose.onNodeWithTag("song_row_a").assertIsOff()
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        compose.onNodeWithText(res.getQuantityString(R.plurals.already_in_playlist, 1, 1)).assertExists()
        compose.onNodeWithTag("song_row_a").performClick()
        compose.onNodeWithTag("selection_add").performClick()
        compose.runOnIdle { assertEquals(listOf("a", "a"), vm.playlists.single().songIds.toList()) }
        compose.onNodeWithTag("playlist_add_songs").assertExists().performClick()
        compose.onNodeWithText(res.getQuantityString(R.plurals.already_in_playlist, 2, 2)).assertExists()
        compose.onNodeWithTag("song_row_a").performClick()
        compose.onNodeWithTag("selection_add").performClick()
        compose.runOnIdle { assertEquals(listOf("a", "a", "a"), vm.playlists.single().songIds.toList()) }
    }

    @Test fun cancelTargetSelectionReturnsWithoutChangingPlaylist() {
        openPlaylistSelection()
        compose.onNodeWithTag("song_row_a").performClick()
        compose.onNodeWithTag("selection_close").performClick()
        compose.onNodeWithTag("playlist_add_songs").assertExists()
        compose.runOnIdle { assertEquals(listOf("a"), vm.playlists.single().songIds.toList()) }
    }

    @Test fun removingSecondOccurrenceDoesNotRemoveTheFirst() {
        compose.runOnIdle {
            val playlist = vm.createPlaylist("Target", vm.songs.value.first())
            vm.addSongToPlaylist(playlist, vm.songs.value[1])
            vm.addSongToPlaylist(playlist, vm.songs.value.first())
        }
        compose.onNodeWithTag("player_expand").performClick()
        compose.onNodeWithTag("panel_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("playlist_row_playlist_0").performClick()
        val remove = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remove_song)
        compose.onNode(hasContentDescription(remove) and hasAnyAncestor(hasTestTag("playlist_song_2"))).performClick()
        compose.runOnIdle { assertEquals(listOf("a", "b"), vm.playlists.single().songIds.toList()) }
    }
}
