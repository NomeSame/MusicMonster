package com.nomesame.musicmonster

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.model.AppLanguage
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.components.AppSettingsButton
import com.nomesame.musicmonster.ui.components.TransportControls
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekControlsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) {
        host = launchComposeHost()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent { AppLanguageContent(AppLanguage.ENGLISH) {
                MyApplicationTheme { block() }
            } }
        }
    }
    @After fun cleanup() {
        if (::host.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { host.finish() }
    }
    @Test fun circularButtonsSendTheirOwnActionsAndDoNotChangeSongs() {
        var rewind = 0; var forward = 0; var previous = 0; var next = 0
        content { TransportControls(false, false, Color.Red, Color.Red, Color.Gray,
            { previous++ }, {}, { next++ }, {}, 25, true, { rewind++ }, { forward++ }) }
        compose.onNodeWithTag("transport_rewind").assertContentDescriptionEquals("Back 25 seconds").performClick()
        compose.onNodeWithTag("transport_forward").assertContentDescriptionEquals("Forward 25 seconds").performClick()
        compose.runOnIdle {
            assertEquals(1, rewind); assertEquals(1, forward)
            assertEquals(0, previous); assertEquals(0, next)
        }
        val left = compose.onNodeWithTag("transport_rewind").fetchSemanticsNode().boundsInRoot
        val prev = compose.onNodeWithTag("transport_previous").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("transport_forward").fetchSemanticsNode().boundsInRoot
        val nxt = compose.onNodeWithTag("transport_next").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= prev.left)
        assertTrue(nxt.right <= right.left)
    }
    @Test fun unavailableSongDisablesBothButtons() {
        content { TransportControls(false, false, Color.Red, Color.Red, Color.Gray,
            {}, {}, {}, {}, canSeek = false,
            onRewind = { fail("Disabled rewind") }, onFastForward = { fail("Disabled forward") }) }
        compose.onNodeWithTag("transport_rewind").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("transport_forward").assertIsNotEnabled().performClick()
    }
    @Test fun narrowLayoutKeepsAllButtonsVisibleAndTouchAreasSeparate() {
        content { Box(Modifier.width(200.dp)) {
            TransportControls(false, false, Color.Red, Color.Red, Color.Gray,
                {}, {}, {}, {}, canSeek = true)
        } }
        val tags = listOf("transport_rewind", "transport_forward", "transport_previous",
            "transport_next", "transport_play_pause", "transport_shuffle")
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        bounds.forEachIndexed { index, a -> bounds.drop(index + 1).forEach { b ->
            assertFalse("Overlapping touch areas", a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
        } }
    }
    @Test fun hiddenButtonsLeaveTheirOldTouchPositionsInertInEveryLayout() {
        val visible = mutableStateOf(true)
        val width = mutableIntStateOf(340)
        var rewind = 0; var forward = 0; var others = 0
        content { Box(Modifier.width(width.intValue.dp)) {
            TransportControls(false, false, Color.Red, Color.Red, Color.Gray,
                { others++ }, { others++ }, { others++ }, { others++ },
                canSeek = true, onRewind = { rewind++ }, onFastForward = { forward++ },
                showSeekButtons = visible.value)
        } }
        listOf(340, 260, 200).forEach { layoutWidth ->
            compose.runOnIdle { width.intValue = layoutWidth; visible.value = true }
            val left = compose.onNodeWithTag("transport_rewind").fetchSemanticsNode().boundsInRoot.center
            val right = compose.onNodeWithTag("transport_forward").fetchSemanticsNode().boundsInRoot.center
            val previous = compose.onNodeWithTag("transport_previous").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { visible.value = false }
            compose.onNodeWithTag("transport_rewind", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("transport_forward", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(previous, compose.onNodeWithTag("transport_previous").fetchSemanticsNode().boundsInRoot)
            val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput { click(left - origin); click(right - origin) }
            compose.runOnIdle { assertEquals(0, rewind); assertEquals(0, forward); assertEquals(0, others) }
        }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("transport_rewind").performClick()
        compose.onNodeWithTag("transport_forward").performClick()
        compose.runOnIdle { assertEquals(1, rewind); assertEquals(1, forward) }
    }

    @Test fun pickerSwitchDefaultsOnAndRetainsItsStateAndIntervalAfterReopening() {
        val enabled = mutableStateOf(true)
        content { AppSettingsButton(AppLanguage.ENGLISH, {}, 35,
            seekButtonsEnabled = enabled.value, onSeekButtonsEnabledChange = { enabled.value = it }) }
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("seek_interval_setting").performClick()
        compose.onNodeWithTag("seek_buttons_switch").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("seek_seconds_35").assertIsSelected()
        compose.onNodeWithTag("seek_picker_done").performClick()
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("seek_interval_setting").performClick()
        compose.onNodeWithTag("seek_buttons_switch").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("seek_seconds_35").assertIsSelected()
    }

    @Test fun scrollPickerOffersFiveSecondStepsAndUpdatesSelection() {
        val seconds = mutableIntStateOf(10)
        content { AppSettingsButton(AppLanguage.ENGLISH, {}, seconds.intValue, onSeekSecondsChange = { seconds.intValue = it }) }
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("seek_interval_setting").performClick()
        compose.onNodeWithTag("seek_seconds_10").assertIsSelected()
        compose.onNodeWithTag("seek_seconds_5").performClick().assertIsSelected()
        compose.onNodeWithTag("seek_interval_list").performScrollToNode(hasTestTag("seek_seconds_300"))
        compose.onNodeWithTag("seek_seconds_300").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(300, seconds.intValue) }
        compose.onNodeWithTag("seek_picker_done").performClick()
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("seek_interval_setting").assertTextEquals("Skip interval: 300 seconds")
    }
}
