package com.nomesame.musicmonster

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.support.v4.media.session.MediaControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.BackgroundRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Real silent playback must keep advancing while the OEM notification is recreated. */
class MediaCardPlaybackContinuityTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private fun <T> main(action: () -> T): T {
        var result: Any? = null
        ins.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(25)
        }
        fail("Media card/playback state timeout")
    }
    private fun field(service: MusicService, name: String) =
        MusicService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun card() = ctx.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == MusicService.NOTIFICATION_ID }?.notification
    private fun color() = (card()?.getLargeIcon()?.loadDrawable(ctx) as? BitmapDrawable)?.bitmap
        ?.let { it.getPixel(it.width / 2, it.height / 2) }

    @Test fun artworkRecreationPreservesPlayingSessionPositionAndForegroundRestoration() {
        ctx.startForegroundService(Intent(ctx, MusicService::class.java))
        await { MusicService.sessionToken != null }
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }
        // The token is set inside onCreate, before ActivityThread registers the service.
        val service = main { (services.get(thread) as Map<*, *>).values.filterIsInstance<MusicService>().single() }
        await { main { field(service, "libraryReady").getBoolean(service) } }
        val player = field(service, "player").get(service) as ExoPlayer
        val prefs = ctx.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val keys = listOf(BackgroundRepository.KEY_URI, BackgroundRepository.KEY_SCRIM,
            BackgroundRepository.KEY_ENABLED, MusicService.PREF_LAST_SONG_ID,
            MusicService.PREF_LAST_POSITION, MusicService.PREF_SHUFFLE_ENABLED)
        val savedPrefs = keys.associateWith { prefs.all[it] }
        val originalItems = main { (0 until player.mediaItemCount).map(player::getMediaItemAt) }
        val originalIndex = main { player.currentMediaItemIndex }
        val originalPosition = main { player.currentPosition }
        val originalPlaying = main { player.playWhenReady }
        val originalState = main { player.playbackState }
        val originalTitles = field(service, "titles").get(service)
        val token = MusicService.sessionToken!!
        val controller = MediaControllerCompat(ctx, token)
        val files = mutableListOf<File>()
        fun image(color: Int): File {
            val file = File.createTempFile("card-continuity-", ".png", ctx.cacheDir).also(files::add)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            return file
        }
        try {
            val silent = File.createTempFile("card-continuity-", ".wav", ctx.cacheDir).also(files::add)
            val dataSize = 44100 * 2 * 20
            val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            wave.putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200)
            wave.putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
            silent.writeBytes(wave.array())
            val red = image(Color.RED)
            val blue = image(Color.BLUE)
            main {
                field(service, "titles").set(service, listOf("Artwork continuity fixture"))
                player.setMediaItem(MediaItem.Builder().setMediaId("card-continuity-fixture")
                    .setUri(Uri.fromFile(silent)).build())
                player.prepare()
                player.play()
                prefs.edit().putString(BackgroundRepository.KEY_URI, Uri.fromFile(red).toString())
                    .putBoolean(BackgroundRepository.KEY_ENABLED, true)
                    .putFloat(BackgroundRepository.KEY_SCRIM, 0f).commit()
            }
            await { main { player.isPlaying } && color() == Color.RED }
            val startPosition = main { player.currentPosition }
            val duration = controller.metadata!!.getLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION)
            main { prefs.edit().putString(BackgroundRepository.KEY_URI, Uri.fromFile(blue).toString()).commit() }
            await { color() == Color.BLUE }
            assertEquals(token, MusicService.sessionToken)
            assertTrue(main { player.isPlaying })
            assertTrue("Playback stopped advancing during card recreation", main { player.currentPosition } > startPosition)
            assertEquals(duration, controller.metadata!!.getLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION))
            assertEquals("card-continuity-fixture", main { player.currentMediaItem!!.mediaId })
            assertTrue("Foreground protection was not restored", card()!!.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        } finally {
            main {
                player.pause()
                field(service, "titles").set(service, originalTitles)
                if (originalItems.isEmpty()) player.clearMediaItems()
                else player.setMediaItems(originalItems, originalIndex.coerceIn(originalItems.indices), originalPosition)
                if (originalState == Player.STATE_READY || originalState == Player.STATE_BUFFERING) player.prepare()
                player.playWhenReady = originalPlaying
                val edit = prefs.edit()
                savedPrefs.forEach { (key, value) ->
                    edit.remove(key)
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                assertTrue(edit.commit())
            }
            files.forEach { assertTrue(it.delete()) }
        }
    }
}