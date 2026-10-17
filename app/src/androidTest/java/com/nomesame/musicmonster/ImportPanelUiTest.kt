package com.nomesame.musicmonster

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import com.nomesame.musicmonster.state.ImportErrorCode
import com.nomesame.musicmonster.state.ImportState
import com.nomesame.musicmonster.ui.components.ImportPanel
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-runtime UI test for the stateless [ImportPanel]: submit is enabled exactly when not
 * active, progress/cancel appear only while active, the failure text is localized and the
 * unwritable-target case offers the folder picker. Assertions look at visible nodes (no no-op).
 */
@RunWith(AndroidJUnit4::class)
class ImportPanelUiTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var host: ComponentActivity
    private val state: MutableState<ImportState> = mutableStateOf(ImportState.Idle)
    private val url: MutableState<String> = mutableStateOf("")

    private var started: String? = null
    private var cancels = 0
    private var folderPicks = 0
    private var savedClipboard: ClipData? = null
    private var clipboardChanged = false

    private val store = ViewModelStore()

    @Before fun setup() {
        host = launchComposeHost()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent {
                MyApplicationTheme(accent = Color(0xFFE58B3C)) {
                    ImportPanel(
                        state = state.value,
                        url = url.value,
                        onUrlChange = { url.value = it },
                        onStart = { started = url.value },
                        onCancel = { cancels++ },
                        onPickFolder = { folderPicks++ },
                    )
                }
            }
        }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (clipboardChanged) {
                val clipboard = context().getSystemService(ClipboardManager::class.java)
                savedClipboard?.let { clipboard.setPrimaryClip(it) } ?: run {
                    if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
            if (::host.isInitialized) host.finish()
            store.clear()
        }
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun idleShowsTheFieldAndSubmitButNoProgressOrCancel() {
        compose.onNodeWithTag("import_panel").assertExists()
        compose.onNodeWithTag("import_url_field").assertExists()
        compose.onNodeWithTag("import_submit").assertExists().assertIsEnabled()
        compose.onNodeWithTag("import_progress").assertDoesNotExist()
        compose.onNodeWithTag("import_cancel").assertDoesNotExist()
        compose.onNodeWithTag("import_error").assertDoesNotExist()
    }

    @Test fun confirmedInputIsForwardedAndOnlyOnceWhileActive() {
        compose.runOnIdle { url.value = "https://example.test/watch?v=abc" }
        compose.onNodeWithTag("import_submit").performClick()
        compose.runOnIdle { assertEquals("https://example.test/watch?v=abc", started) }

        // While active the submit button is disabled, so it cannot start a second job.
        compose.runOnIdle { state.value = ImportState.Active("job-1", PluginProtocol.JobState.DOWNLOADING, 42) }
        compose.onNodeWithTag("import_submit").assertIsNotEnabled()
        compose.onNodeWithTag("import_paste").assertIsNotEnabled()
        compose.onNodeWithTag("import_progress").assertExists()
        compose.onNodeWithTag("import_cancel").assertExists()
        compose.onNodeWithText(context().getString(R.string.import_progress, 42)).assertExists()
    }

    @Test fun cancelDuringActiveIsForwarded() {
        compose.runOnIdle { state.value = ImportState.Active("job-1", PluginProtocol.JobState.CONVERTING, 80) }
        compose.onNodeWithTag("import_cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun failureShowsTheLocalizedErrorText() {
        compose.runOnIdle { state.value = ImportState.Failed(PluginProtocol.ErrorCode.NETWORK) }
        compose.onNodeWithTag("import_error").assertExists()
        compose.onNodeWithText(context().getString(R.string.import_error_network)).assertExists()
        compose.onNodeWithTag("import_pick_folder").assertDoesNotExist()
        compose.onNodeWithTag("import_progress").assertDoesNotExist()
    }

    @Test fun unwritableTargetOffersTheFolderPicker() {
        compose.runOnIdle { state.value = ImportState.Failed(ImportErrorCode.TARGET_NOT_WRITABLE) }
        compose.onNodeWithText(context().getString(R.string.import_error_target_not_writable)).assertExists()
        compose.onNodeWithTag("import_pick_folder").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, folderPicks) }
    }

    @Test fun successShowsTheWrittenFileName() {
        compose.runOnIdle { state.value = ImportState.Succeeded("My Song.mp3") }
        compose.onNodeWithTag("import_success").assertExists()
        compose.onNodeWithText(context().getString(R.string.import_success, "My Song.mp3")).assertExists()
        compose.onNodeWithTag("import_submit").assertIsEnabled()
    }

    @Test fun cancelledShowsItsTextAndKeepsSubmitEnabled() {
        compose.runOnIdle { state.value = ImportState.Cancelled }
        compose.onNodeWithTag("import_cancelled").assertExists()
        compose.onNodeWithText(context().getString(R.string.import_cancelled)).assertExists()
        compose.onNodeWithTag("import_submit").assertIsEnabled()
    }

    private fun copyText(text: String) = compose.runOnIdle {
        val clipboard = context().getSystemService(ClipboardManager::class.java)
        if (!clipboardChanged) {
            savedClipboard = clipboard.primaryClip
            clipboardChanged = true
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("import-test", text))
    }

    @Test fun pasteReplacesTheFieldWithoutStartingADownload() {
        compose.runOnIdle { url.value = "old text" }
        copyText("https://youtu.be/bWtYzU40N-M")
        compose.onNodeWithTag("import_paste").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("https://youtu.be/bWtYzU40N-M", url.value)
            assertNull(started)
        }
    }

    @Test fun pasteKeepsTheExistingInputWhenClipboardIsEmpty() {
        compose.runOnIdle { url.value = "existing input" }
        copyText("")
        compose.onNodeWithTag("import_paste").performClick()
        compose.runOnIdle { assertEquals("existing input", url.value) }
    }

    @Test fun pasteRespectsTheSameLengthLimitAsTyping() {
        copyText("a".repeat(3000))
        compose.onNodeWithTag("import_paste").performClick()
        compose.runOnIdle { assertEquals(2048, url.value.length) }
    }
}
