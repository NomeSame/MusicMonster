package com.nomesame.musicmonster
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.*
import com.nomesame.musicmonster.state.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [30])
class SongRenameControllerTest {
 private val song = Song("old", "old", Uri.parse("content://fixture/old"), 1000, "old.mp3")
 private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
  .getSharedPreferences("rename-controller", 0).also { it.edit().clear().commit() }
 @Test fun validationAndDuplicateSubmitAndPermanentReferences() = runTest {
  val prefs = prefs()
  prefs.edit().putString("playlists_json", """[{"id":"p","name":"P","songs":["old","x","old"]}]""").putString("last_song_id","old").commit()
  var calls = 0; var updated: Song? = null
  val renamer = object : SongRenamer {
   override fun name(song: Song) = "old.mp3"
   override fun rename(song: Song, original: String, input: String, mayRequestPermission: Boolean): RenameOutcome {
    calls++; return RenameOutcome.Success(song.copy(id="new", title=input, fileName="$input.mp3"))
   }
  }
  val controller = SongRenameController(renamer, SongReferenceRepository(prefs), this, { _, new -> updated=new }, StandardTestDispatcher(testScheduler))
  controller.open(song); runCurrent()
  controller.edit("../bad"); controller.submit(); runCurrent()
  assertEquals(RenameError.INVALID_NAME, controller.state.value?.error); assertEquals(0,calls)
  controller.edit("new"); controller.submit(); controller.submit(); runCurrent()
  assertNull(controller.state.value); assertEquals(1,calls); assertEquals("new",updated?.id)
  assertEquals("new",prefs.getString("last_song_id",null))
  val ids = org.json.JSONArray(prefs.getString("playlists_json",null)).getJSONObject(0).getJSONArray("songs")
  assertEquals(listOf("new","x","new"), (0 until ids.length()).map { ids.getString(it) })
 }
 @Test fun denialLeavesFileAndReferencesIntactAndRetryCannotLoopPermission() = runTest {
  var calls = 0; val flags=mutableListOf<Boolean>()
  val renamer = object : SongRenamer {
   override fun name(song: Song) = "old.mp3"
   override fun rename(song: Song, original: String, input: String, mayRequestPermission: Boolean): RenameOutcome {
    calls++;flags+=mayRequestPermission
    return if (mayRequestPermission) RenameOutcome.LegacyPermission else RenameOutcome.Error(RenameError.READ_ONLY)
   }
  }
  val controller=SongRenameController(renamer,SongReferenceRepository(prefs()),this,{_,_->fail()},StandardTestDispatcher(testScheduler))
  controller.open(song);runCurrent();controller.edit("new");controller.submit();runCurrent()
  assertTrue(controller.state.value!!.busy)
  controller.permissionResult(false);runCurrent();assertEquals(1,calls);assertEquals(RenameError.READ_ONLY,controller.state.value?.error)
  controller.submit();runCurrent();controller.permissionResult(true);runCurrent()
  assertEquals(listOf(true,true,false),flags);assertFalse(controller.state.value!!.busy)
 }
 @Test fun unexpectedProviderExceptionDoesNotLeaveBusyDialog() = runTest {
  val renamer=object:SongRenamer {
   override fun name(song:Song)="old.mp3"
   override fun rename(song:Song,original:String,input:String,mayRequestPermission:Boolean):RenameOutcome=error("provider died")
  }
  val controller=SongRenameController(renamer,SongReferenceRepository(prefs()),this,{_,_->fail()},StandardTestDispatcher(testScheduler))
  controller.open(song);runCurrent();controller.edit("new");controller.submit();runCurrent()
  assertFalse(controller.state.value!!.busy);assertEquals(RenameError.FAILED,controller.state.value?.error)
 }
 @Test fun journalRecoversAfterRestartAndRejectsCorruptPlaylists() {
  val prefs=prefs();val refs=SongReferenceRepository(prefs)
  prefs.edit().putString("playlists_json","""[{"songs":["old","old"]}]""").putString("last_song_id","old").commit()
  assertTrue(refs.prepare(song,"new.mp3"))
  SongReferenceRepository(prefs).recover(listOf(song.copy(id="new",fileName="new.mp3")))
  assertEquals("new",prefs.getString("last_song_id",null));assertFalse(prefs.contains("pending_file_rename"))
  prefs.edit().putString("playlists_json","broken").commit()
  assertFalse(refs.prepare(song,"another.mp3"))
 }
 @Test fun ambiguousRecoveryAndCorruptJournalNeverGuessAFile() {
  val prefs=prefs();val refs=SongReferenceRepository(prefs)
  prefs.edit().putString("last_song_id","old").commit()
  assertTrue(refs.prepare(song,"new.mp3"))
  refs.recover(listOf(song.copy(id="one",fileName="new.mp3"),song.copy(id="two",fileName="new.mp3")))
  assertEquals("old",prefs.getString("last_song_id",null));assertTrue(prefs.contains("pending_file_rename"))
  prefs.edit().putString("pending_file_rename","broken").commit()
  assertFalse(refs.prepare(song,"other.mp3"))
 }
 @Test fun stableMediaStoreIdentityPreservesPositionAndPlaylistDuplicates() {
  val prefs=prefs();val refs=SongReferenceRepository(prefs)
  prefs.edit().putString("last_song_id","old").putLong("last_position",3456L)
   .putString("playlists_json","""[{"songs":["old","old"]}]""").commit()
  assertTrue(refs.prepare(song,"new.mp3"));assertTrue(refs.remap("old","old"))
  assertEquals(3456L,prefs.getLong("last_position",0));assertEquals("old",prefs.getString("last_song_id",null))
  assertEquals(2,org.json.JSONArray(prefs.getString("playlists_json",null)).getJSONObject(0).getJSONArray("songs").length())
 }

}
