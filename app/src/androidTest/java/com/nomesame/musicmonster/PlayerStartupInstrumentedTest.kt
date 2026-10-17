package com.nomesame.musicmonster

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerStartupInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun assertLibrary(fixture: PlayerLaunchFixture) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("player_expand").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player_expand").assertContentDescriptionEquals("Show equalizer / timer / playlists")
        compose.onAllNodesWithText(fixture.name).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("song_list_foreground").assertIsDisplayed()
        compose.onNodeWithTag("video_player").assertDoesNotExist()
    }
    @Test fun launcherReturnsFromOpenPanelsToTheSongsAndPlayer() {
        PlayerLaunchFixture().use { fixture ->
            val first = fixture.launch()
            assertLibrary(fixture)
            compose.onNodeWithTag("player_expand").performClick()
            compose.onNodeWithTag("player_expand").assertContentDescriptionEquals("Show list")
            val reopened = fixture.launch()
            assertSame("Launcher must reuse the existing player", first, reopened)
            assertLibrary(fixture)
        }
    }
    @Test fun recreatedEntryDoesNotRestoreExpandedPanels() {
        PlayerLaunchFixture().use { fixture ->
            val first = fixture.launch()
            assertLibrary(fixture)
            compose.onNodeWithTag("player_expand").performClick()
            fixture.inst.runOnMainSync { first.recreate() }
            fixture.host = awaitResumedTestActivity(MainActivity::class.java, first)
            assertLibrary(fixture)
        }
    }
}
