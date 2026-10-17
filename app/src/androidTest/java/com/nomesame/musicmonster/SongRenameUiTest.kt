package com.nomesame.musicmonster
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.RenameError
import com.nomesame.musicmonster.state.RenameForm
import com.nomesame.musicmonster.ui.components.RenameSongDialog
import com.nomesame.musicmonster.ui.components.SongSelectionBar
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.*
import org.junit.Assert.*
class SongRenameUiTest {
 @get:Rule val compose=createEmptyComposeRule()
 private lateinit var host:ComponentActivity
 private val song=Song("x","Old",Uri.EMPTY,1000,"Old.mp3")
 @Before fun setup() { host=launchComposeHost() }
 @After fun cleanup() { InstrumentationRegistry.getInstrumentation().runOnMainSync { host.finish() } }
 @Test fun editingAndConfirmationPassActualInputAndBusyCannotSubmit() {
  var form by mutableStateOf(RenameForm(song,"Old.mp3","Old",false))
  var saved="";var dismissals=0
  InstrumentationRegistry.getInstrumentation().runOnMainSync {
   host.setContent { MyApplicationTheme {
    RenameSongDialog(form,{ form=form.copy(input=it) },{ saved=form.input;form=form.copy(busy=true) },{ dismissals++ },{})
   } }
  }
  compose.onNodeWithTag("rename_input").performTextReplacement("New Song")
  compose.onNodeWithTag("rename_confirm").performClick()
  compose.runOnIdle { assertEquals("New Song",saved);assertEquals(0,dismissals) }
  compose.onNodeWithTag("rename_confirm").assertIsNotEnabled()
  compose.onNodeWithTag("rename_input").assertIsNotEnabled()
 }
 @Test fun selectionOnlyAllowsExactlyOneFile() {
  var count by mutableIntStateOf(2);var calls=0
  InstrumentationRegistry.getInstrumentation().runOnMainSync {
   host.setContent { MyApplicationTheme {
    SongSelectionBar(count,false,false,{},{},{},{},onRename={calls++})
   } }
  }
  compose.onNodeWithTag("selection_rename").assertIsNotEnabled()
  compose.runOnIdle { count=1 }
  compose.onNodeWithTag("selection_rename").assertIsEnabled().performClick()
  compose.runOnIdle { assertEquals(1,calls) }
 }
}
