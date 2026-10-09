package com.nomesame.musicmonster

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.model.AppLanguage
import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.components.ArtworkCropDialog
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkCropUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private val position = mutableStateOf(ArtworkCrop())
    private val source = mutableStateOf<Bitmap?>(null)
    private val fixtures = mutableListOf<Bitmap>()
    private fun stripes(horizontal: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(if (horizontal) 192 else 64, if (horizontal) 64 else 192,
            Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE)
            .forEachIndexed { index, color ->
                val paint = android.graphics.Paint().apply { this.color = color }
                canvas.drawRect(if (horizontal) index * 64f else 0f, if (horizontal) 0f else index * 64f,
                    if (horizontal) (index + 1) * 64f else 64f,
                    if (horizontal) 64f else (index + 1) * 64f, paint)
            }
        fixtures.add(bitmap)
        return bitmap
    }
    @Before fun setup() {
        host = launchComposeHost()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            source.value = stripes(false)
            host.setContent {
                AppLanguageContent(AppLanguage.GERMAN) {
                    MyApplicationTheme(accent = Color.Yellow) {
                        ArtworkCropDialog(source.value, false, position.value, 0f,
                            { position.value = it }, {})
                    }
                }
            }
        }
    }
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { if (::host.isInitialized) host.finish() }
        // Compose may still reference a just-dismissed bitmap; let GC release fixtures.
        fixtures.clear()
    }
    private fun setProgress(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
    }
    private fun previewColor(): Int {
        val image = compose.onNodeWithTag("artwork_crop_preview").performScrollTo().captureToImage()
        return image.toPixelMap()[image.width / 2, image.height / 2].toArgb()
    }

    @Test fun portraitSliderChangesTheActualPreviewPixels() {
        compose.onNodeWithTag("artwork_crop_x").assertIsNotEnabled()
        compose.onNodeWithTag("artwork_crop_y").assertIsEnabled()
        assertEquals(android.graphics.Color.GREEN, previewColor())
        setProgress("artwork_crop_y", 0f)
        assertEquals(android.graphics.Color.RED, previewColor())
        setProgress("artwork_crop_y", 1f)
        assertEquals(android.graphics.Color.BLUE, previewColor())
    }
    @Test fun draggingThePreviewUpdatesPositionWithoutChangingTheOtherAxis() {
        compose.onNodeWithTag("artwork_crop_preview").performScrollTo().performTouchInput {
            swipe(center, center + Offset(0f, height * 0.2f))
        }
        compose.runOnIdle {
            assertTrue(position.value.y < 0.5f)
            assertEquals(0.5f, position.value.x, 0f)
        }
    }
    @Test fun switchingCurrentBackgroundChangesTheAvailableAxisAndResetCentersIt() {
        compose.runOnIdle { source.value = stripes(true) }
        compose.onNodeWithTag("artwork_crop_x").assertIsEnabled()
        compose.onNodeWithTag("artwork_crop_y").assertIsNotEnabled()
        setProgress("artwork_crop_x", 1f)
        assertEquals(android.graphics.Color.BLUE, previewColor())
        compose.onNodeWithText("Zentrieren").performClick()
        compose.runOnIdle { assertEquals(ArtworkCrop(), position.value) }
        assertEquals(android.graphics.Color.GREEN, previewColor())
    }
    @Test fun missingOrDisabledBackgroundHasNoEditableSliders() {
        compose.runOnIdle { source.value = null }
        compose.onNodeWithText("Kein Hintergrundbild verfügbar.").assertIsDisplayed()
        compose.onNodeWithTag("artwork_crop_x").assertIsNotEnabled()
        compose.onNodeWithTag("artwork_crop_y").assertIsNotEnabled()
    }
    @Test fun zoomEnablesBothAxesUpdatesPreviewAndResetReturnsToOne() {
        setProgress("artwork_crop_zoom", 2f)
        compose.onNodeWithTag("artwork_crop_x").assertIsEnabled()
        compose.onNodeWithTag("artwork_crop_y").assertIsEnabled()
        setProgress("artwork_crop_y", 0f)
        assertEquals(android.graphics.Color.RED, previewColor())
        compose.runOnIdle { assertEquals(2f, position.value.zoom, 0f) }
        compose.onNodeWithText("Zentrieren").performClick()
        compose.runOnIdle { assertEquals(ArtworkCrop(), position.value) }
        compose.onNodeWithTag("artwork_crop_x").assertIsNotEnabled()
    }

}
