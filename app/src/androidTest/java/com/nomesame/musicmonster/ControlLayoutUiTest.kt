package com.nomesame.musicmonster

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.model.AppLanguage
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.components.ControlLabel
import com.nomesame.musicmonster.ui.components.SongSelectionBar
import com.nomesame.musicmonster.ui.screens.SleepTimerPanel
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlLayoutUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private val language = mutableStateOf(AppLanguage.GERMAN)
    private val width = mutableStateOf(280)
    private val fontScale = mutableStateOf(1f)
    private val starts = mutableListOf<Long>()
    private val panel = mutableStateOf("timer")
    private val selectedAll = mutableStateOf(false)
    private val longLabel = "Donaudampfschifffahrtsgesellschaftskapitän"
    private val newlineLabel = "Eine sehr lange\nBeschriftung"

    @Before fun setup() {
        host = launchComposeHost()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                    AppLanguageContent(language.value) {
                        MyApplicationTheme(accent = Color(0xFFE58B3C)) {
                            Column(Modifier.width(width.value.dp).verticalScroll(rememberScrollState())) {
                                if (panel.value == "labels") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("OK", longLabel, newlineLabel).forEach { caption ->
                                            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                                                ControlLabel(caption)
                                            }
                                        }
                                    }
                                } else if (panel.value == "selection") {
                                    SongSelectionBar(1, selectedAll.value, true, {}, {},
                                        { selectedAll.value = !selectedAll.value }, {}, longLabel)
                                } else {
                                SleepTimerPanel(Color.White, Color.Gray, Color.Yellow,
                                    { "0:00" }, { duration, _ -> starts.add(duration) }, {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (::host.isInitialized) host.finish()
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun assertEqualFields() {
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals(3, fields.size)
        fields.drop(1).forEach {
            assertEquals(fields.first().boundsInRoot.height, it.boundsInRoot.height, 1f)
            assertEquals(fields.first().boundsInRoot.width, it.boundsInRoot.width, 1f)
        }
    }

    @Test fun germanSecondsNeverWrapsOrEnlargesItsField() {
        assertEquals(1, layout("Sekunden").lineCount)
        assertEqualFields()
    }

    @Test fun narrowWidthAndLargeFontKeepEveryFieldSingleLineAndEqual() {
        compose.runOnIdle { width.value = 240; fontScale.value = 1.5f }
        listOf("Stunden", "Minuten", "Sekunden").forEach { assertEquals(1, layout(it).lineCount) }
        assertEqualFields()
    }

    @Test fun switchingLanguagePreservesFieldGeometryInputAndTimerActions() {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[2].performTextInput("12")
        val initial = fields.fetchSemanticsNodes().map { it.boundsInRoot.height }
        compose.runOnIdle { language.value = AppLanguage.ENGLISH }
        listOf("Hours", "Minutes", "Seconds").forEach { assertEquals(1, layout(it).lineCount) }
        assertEqualFields()
        assertEquals(initial, fields.fetchSemanticsNodes().map { it.boundsInRoot.height })
        fields[2].assertTextContains("12")
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(12000L), starts) }
    }

    @Test fun longAndExplicitMultilineCaptionsEllipsizeWithoutShrinkingTextOrButtons() {
        compose.runOnIdle { panel.value = "labels"; width.value = 240; fontScale.value = 1.5f }
        val captions = listOf("OK", longLabel, newlineLabel)
        val layouts = captions.map(::layout)
        layouts.forEach {
            assertEquals(1, it.lineCount)
            assertEquals(14.sp, it.layoutInput.style.fontSize)
        }
        assertTrue(layouts[1].isLineEllipsized(0))
        val buttons = captions.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        buttons.drop(1).forEach {
            assertEquals(buttons.first().height, it.height, 1f)
            assertEquals(buttons.first().width, it.width, 1f)
        }
        // Semantics expose the original caption, not only the visible abbreviated glyphs.
        compose.onNodeWithText(longLabel).assertTextEquals(longLabel).assertHasClickAction()
    }

    @Test fun selectionButtonsKeepGeometryAcrossLanguageAndSelectAllStateChanges() {
        compose.runOnIdle { panel.value = "selection"; width.value = 240; fontScale.value = 1.5f }
        val tags = listOf("selection_add", "selection_create", "selection_all")
        val initial = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        compose.onNodeWithTag("selection_all").performClick()
        compose.runOnIdle { language.value = AppLanguage.ENGLISH }
        tags.forEachIndexed { index, tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals(initial[index].height, bounds.height, 1f)
            assertEquals(initial[index].width, bounds.width, 1f)
        }
        assertEquals(1, layout("Add to $longLabel").lineCount)
        assertEquals(1, layout("Create playlist").lineCount)
        assertEquals(1, layout("Deselect all").lineCount)
    }
}
