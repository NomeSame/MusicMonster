package com.nomesame.musicmonster
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.*
import com.nomesame.musicmonster.playback.SongQueueRenamer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class SongRenameInstrumentedTest {
 private val inst=InstrumentationRegistry.getInstrumentation()
 private val context=inst.targetContext
 private val resolver get()=context.contentResolver
 private val folder get()=File(context.cacheDir,"rename-fixtures").apply { mkdirs() }
 private val created=mutableListOf<File>()
 private val media=mutableListOf<Uri>()
 private val prefs get()=context.getSharedPreferences("rename-runtime-fixture",Context.MODE_PRIVATE)
 private val tree get()=DocumentsContract.buildTreeDocumentUri(context.packageName+".rename-fixture","root")
 private val parent get()=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
 @Before fun setup() { TestPermissions.grantAll();prefs.edit().clear().commit() }
 @After fun cleanup() { com.nomesame.musicmonster.fixtures.RenameDocumentsProvider.beforeOpen=null;media.forEach { runCatching { resolver.delete(it,null,null) } };created.forEach { it.delete() };prefs.edit().clear().commit() }
 private fun fixture(prefix:String="source"):Song {
  val name=prefix+UUID.randomUUID()+".wav";val f=File(folder,name);f.writeBytes(wav());created+=f
  val uri=DocumentsContract.buildDocumentUriUsingTree(tree,name)
  return Song(uri.toString(),name.removeSuffix(".wav"),uri,10000,name,parent)
 }
 private fun wav():ByteArray {
  val size=160000;val buffer=java.nio.ByteBuffer.allocate(44+size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
  buffer.put("RIFF".toByteArray()).putInt(size+36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
   .putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
  repeat(size/2) { buffer.putShort((kotlin.math.sin(it*0.1)*1000).toInt().toShort()) };return buffer.array()
 }
 @Test fun safRenamesActualFileReturnsNewUriAndRecoversEveryPlaylistOccurrence() {
  val song=fixture();val newName="renamed"+UUID.randomUUID();val oldBytes=resolver.openInputStream(song.uri)!!.use { it.readBytes() }
  prefs.edit().putString("library_tree_uri",tree.toString())
   .putString("playlists_json","""[{"id":"p","name":"P","songs":["${song.id}","other","${song.id}"]}]""")
   .putString("last_song_id",song.id).commit()
  assertTrue(SongReferenceRepository(prefs).prepare(song,newName+".wav"))
  val outcome=SongRenameRepository(context).rename(song,song.fileName!!,newName,true)
  assertTrue(outcome.toString(),outcome is RenameOutcome.Success)
  val updated=(outcome as RenameOutcome.Success).song;created+=File(folder,updated.fileName!!)
  assertNotEquals(song.uri,updated.uri);assertFalse(File(folder,song.fileName).exists())
  assertArrayEquals(oldBytes,resolver.openInputStream(updated.uri)!!.use { it.readBytes() })
  // New repository instances model a process restart between provider edit and reference commit.
  val reloaded=SongRepository(context,prefs).load()
  assertTrue(reloaded.any { it.id==updated.id });assertEquals(updated.id,prefs.getString("last_song_id",null))
  val ids=org.json.JSONArray(prefs.getString("playlists_json",null)).getJSONObject(0).getJSONArray("songs")
  assertEquals(listOf(updated.id,"other",updated.id),(0 until ids.length()).map { ids.getString(it) })
  assertFalse(prefs.contains("pending_file_rename"))
 }
 @Test fun safCollisionReadOnlyMissingAndInvalidNeverOverwrite() {
  val song=fixture();val other=fixture("target");val repo=SongRenameRepository(context)
  val otherBytes=File(folder,other.fileName!!).readBytes()
  assertEquals(RenameOutcome.Error(RenameError.NAME_EXISTS),repo.rename(song,song.fileName!!,other.title,true))
  assertArrayEquals(otherBytes,File(folder,other.fileName).readBytes());assertTrue(File(folder,song.fileName).exists())
  val locked=fixture("locked");assertEquals(RenameOutcome.Error(RenameError.READ_ONLY),repo.rename(locked,locked.fileName!!,"different",true))
  assertEquals(RenameOutcome.Error(RenameError.INVALID_NAME),repo.rename(song,song.fileName,"../bad",true))
  File(folder,song.fileName).delete()
  assertEquals(RenameOutcome.Error(RenameError.MISSING),repo.rename(song,song.fileName,"gone",true))
 }
 @Test fun mediaStoreRenamesRealFileKeepsStableIdAndRejectsDuplicate() {
  val oldName="old"+UUID.randomUUID()+".wav";val newName="new"+UUID.randomUUID()
  val values=ContentValues().apply {
   put(MediaStore.Audio.Media.DISPLAY_NAME,oldName);put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav")
   put(MediaStore.Audio.Media.IS_MUSIC,1);put(MediaStore.Audio.Media.TITLE,"Old")
   if(Build.VERSION.SDK_INT>=29) put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/MusicMonsterRenameTests/")
   else {
    val file=File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),oldName)
    file.parentFile!!.mkdirs();file.writeBytes(wav());created+=file;put(MediaStore.Audio.Media.DATA,file.absolutePath)
   }
  }
  val uri=resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)!!;media+=uri
  resolver.openOutputStream(uri,"w")!!.use { it.write(wav()) }
  val song=Song(ContentUris.parseId(uri).toString(),"Old",uri,10000,oldName)
  val takenName="taken"+UUID.randomUUID()+".wav"
  val collisionValues=ContentValues(values).apply {
   put(MediaStore.Audio.Media.DISPLAY_NAME,takenName)
   if(Build.VERSION.SDK_INT<29) {
    val target=File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),takenName)
    put(MediaStore.Audio.Media.DATA,target.absolutePath);created+=target
   }
  }
  val taken=resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,collisionValues)!!;media+=taken
  resolver.openOutputStream(taken,"w")!!.use { it.write(wav()) }
  assertEquals(RenameOutcome.Error(RenameError.NAME_EXISTS),SongRenameRepository(context).rename(song,oldName,takenName,true))
  assertArrayEquals(wav(),resolver.openInputStream(taken)!!.use { it.readBytes() })
  assertEquals(oldName,SongRenameRepository(context).name(song))
  val result=SongRenameRepository(context).rename(song,oldName,newName,true)
  assertTrue(result.toString(),result is RenameOutcome.Success)
  val updated=(result as RenameOutcome.Success).song
  assertEquals(song.id,updated.id);assertEquals(uri,updated.uri);assertEquals(newName+".wav",updated.fileName)
  assertArrayEquals(wav(),resolver.openInputStream(uri)!!.use { it.readBytes() })
  resolver.query(uri,arrayOf(MediaStore.MediaColumns.DATA),null,null,null)!!.use {
   assertTrue(it.moveToFirst());val actual=File(it.getString(0));created+=actual
   assertEquals(newName+".wav",actual.name);assertTrue(actual.exists());assertFalse(File(actual.parentFile,oldName).exists())
  }
 }
 @Test fun realPlayerPreservesQueueDuplicatesPositionAndShuffleAfterUriChange() {
  val song=fixture();val other=fixture("other");lateinit var player:ExoPlayer
  inst.runOnMainSync {
   player=ExoPlayer.Builder(context).build()
   player.setMediaItems(listOf(song,other,song).map { MediaItem.Builder().setMediaId(it.id).setUri(it.uri).build() },2,3000)
   player.shuffleModeEnabled=true;player.playWhenReady=false;player.prepare()
  }
  fun traversal(): List<Int> {
   val result=mutableListOf<Int>();val timeline=player.currentTimeline
   var index=timeline.getFirstWindowIndex(true)
   while(index!=androidx.media3.common.C.INDEX_UNSET && result.size<player.mediaItemCount) {
    result+=index;index=timeline.getNextWindowIndex(index,androidx.media3.common.Player.REPEAT_MODE_OFF,true)
   };return result
  }
  var order:List<Int> = emptyList()
  inst.runOnMainSync { order=traversal() }
  try {
   val name="queue"+UUID.randomUUID();val result=SongRenameRepository(context).rename(song,song.fileName!!,name,true)
   assertTrue(result.toString(),result is RenameOutcome.Success);val updated=(result as RenameOutcome.Success).song
   created+=File(folder,updated.fileName!!)
   inst.runOnMainSync {
    SongQueueRenamer.apply(player,song.id,updated)
    assertEquals(3,player.mediaItemCount);assertEquals(2,player.currentMediaItemIndex)
    assertEquals(3000L,player.currentPosition);assertTrue(player.shuffleModeEnabled);assertFalse(player.playWhenReady)
    assertEquals(listOf(updated.id,other.id,updated.id),(0..2).map { player.getMediaItemAt(it).mediaId })
    assertEquals(updated.uri,player.currentMediaItem!!.localConfiguration!!.uri)
    assertEquals(order,traversal())
   }
   val deadline=android.os.SystemClock.uptimeMillis()+10000
   var ready=false
   while(!ready && android.os.SystemClock.uptimeMillis()<deadline) {
    inst.runOnMainSync { player.playerError?.let { throw AssertionError("Renamed audio cannot play",it) };ready=player.playbackState==androidx.media3.common.Player.STATE_READY }
    android.os.SystemClock.sleep(30)
   }
   assertTrue("New URI must actually decode",ready)
   inst.runOnMainSync { player.playWhenReady=true }
   var playing=false
   val playingDeadline=android.os.SystemClock.uptimeMillis()+5000
   while(!playing && android.os.SystemClock.uptimeMillis()<playingDeadline) {
    inst.runOnMainSync { playing=player.isPlaying };android.os.SystemClock.sleep(20)
   }
   assertTrue("Fixture must actually be playing before the next rename",playing)
   val liveResult=SongRenameRepository(context).rename(updated,updated.fileName!!,"playing"+UUID.randomUUID(),true)
   assertTrue(liveResult.toString(),liveResult is RenameOutcome.Success)
   val live=(liveResult as RenameOutcome.Success).song;created+=File(folder,live.fileName!!)
   inst.runOnMainSync {
    val before=player.currentPosition
    SongQueueRenamer.apply(player,updated.id,live)
    assertTrue(player.playWhenReady);assertEquals(2,player.currentMediaItemIndex)
    assertTrue(kotlin.math.abs(before-player.currentPosition)<250);assertEquals(order,traversal())
   }
   playing=false
   val resumedDeadline=android.os.SystemClock.uptimeMillis()+5000
   while(!playing && android.os.SystemClock.uptimeMillis()<resumedDeadline) {
    inst.runOnMainSync { player.playerError?.let { throw AssertionError("Live rename playback failed",it) };playing=player.isPlaying }
    android.os.SystemClock.sleep(20)
   }
   assertTrue("Playback must resume from the renamed URI",playing)
  } finally { inst.runOnMainSync { player.release() } }
 }
 @Test fun oldLoadFailureDeliveredAfterReplacementRecoversUsingNewUri() {
  val song=fixture("inflight");val entered=java.util.concurrent.CountDownLatch(1)
  val release=java.util.concurrent.CountDownLatch(1);lateinit var player:ExoPlayer
  com.nomesame.musicmonster.fixtures.RenameDocumentsProvider.beforeOpen={ id ->
   if(id==song.fileName) { entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) }
  }
  inst.runOnMainSync {
   player=ExoPlayer.Builder(context).build()
   player.setMediaItem(MediaItem.Builder().setMediaId(song.id).setUri(song.uri).build(),2000)
   player.playWhenReady=true;player.prepare()
  }
  try {
   assertTrue("The old URI must have an in-flight open",entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
   // Keep the application thread occupied so the old load error reaches it after replacement.
   inst.runOnMainSync {
    val result=SongRenameRepository(context).rename(song,song.fileName!!,"inflight-new"+UUID.randomUUID(),true)
    assertTrue(result.toString(),result is RenameOutcome.Success)
    val renamed=(result as RenameOutcome.Success).song;created+=File(folder,renamed.fileName!!)
    release.countDown()
    android.os.SystemClock.sleep(250)
    SongQueueRenamer.apply(player,song.id,renamed)
    assertEquals(renamed.uri,player.currentMediaItem!!.localConfiguration!!.uri)
   }
   var playing=false;val deadline=android.os.SystemClock.uptimeMillis()+10000
   while(!playing && android.os.SystemClock.uptimeMillis()<deadline) {
    inst.runOnMainSync { player.playerError?.let { throw AssertionError("Stale old-URI error stopped the new source",it) };playing=player.isPlaying }
    android.os.SystemClock.sleep(25)
   }
   assertTrue("Replacement must recover and actually play",playing)
  } finally {
   release.countDown();com.nomesame.musicmonster.fixtures.RenameDocumentsProvider.beforeOpen=null
   inst.runOnMainSync { player.release() }
  }
 }

}
