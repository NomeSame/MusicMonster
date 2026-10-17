package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.playback.VideoPlaybackConnection
import com.nomesame.musicmonster.ui.components.VideoPlayerDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Actual video frames and audio continuity through the existing service player.
 * owned-video.mp4 is synthetic (blue frames + 440Hz tone), generated with FFmpeg:
 * -f lavfi -i color=c=blue:s=426x240:r=12 -f lavfi -i sine=frequency=440:sample_rate=44100
 * -t 8 -c:v libx264 -pix_fmt yuv420p -crf 35 -c:a aac -b:a 48k -movflags +faststart
 */
class VideoPlaybackInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Video playback condition timed out", condition())
    }
    @Test fun visibleVideoUsesOnePlayerAndClosingItKeepsAudioPositionAndQueue() {
        val host = launchComposeHost()
        val connection = VideoPlaybackConnection(ctx)
        val file = File.createTempFile("owned-video-", ".mp4", ctx.cacheDir)
        inst.context.assets.open("owned-video.mp4").use { input -> file.outputStream().use(input::copyTo) }
        // Mirror MainViewModel: playback is a started service before the UI binds.
        // A bound-only fixture is destroyed after its last unbind by Android design.
        main {
            androidx.core.content.ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, MusicService::class.java))
            connection.connect()
        }
        await { connection.player.value != null }
        val player = requireNotNull(connection.player.value)
        // Wait for the service's initial library scan before installing the owned queue.
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }
        val service = main { (services.get(thread) as Map<*, *>).values.filterIsInstance<MusicService>().single() }
        val ready = MusicService::class.java.getDeclaredField("libraryReady").apply { isAccessible = true }
        await { main { ready.getBoolean(service) } }
        val oldItems = main { (0 until player.mediaItemCount).map(player::getMediaItemAt) }
        val oldIndex = main { player.currentMediaItemIndex }
        val oldPosition = main { player.currentPosition }
        val oldPlaying = main { player.playWhenReady }
        val oldVolume = main { player.volume }
        val oldState = main { player.playbackState }
        val prefs = ctx.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val keys = listOf(MusicService.PREF_LAST_SONG_ID, MusicService.PREF_LAST_POSITION, MusicService.PREF_SHUFFLE_ENABLED)
        val saved = keys.associateWith { prefs.all[it] }
        var firstFrame = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        var open by mutableStateOf(true)
        try {
            main {
                player.addListener(listener)
                player.volume = 0f
                player.setMediaItem(MediaItem.Builder().setMediaId("owned-video").setUri(Uri.fromFile(file)).build())
                player.prepare()
                player.play()
                host.setContent { MaterialTheme { if (open) VideoPlayerDialog { open = false } } }
            }
            compose.onNodeWithTag("video_player").assertIsDisplayed()
            await { main { firstFrame && player.isPlaying && player.videoSize.height == 240 } }
            main { player.seekTo(1000) }
            await { main { player.currentPosition >= 1000 } }
            // A decoder callback alone is insufficient: verify a real blue fixture frame
            // reaches the screen after seeking (including compositor/surface timing).
            await {
                val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
                try {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    android.graphics.Color.blue(pixel) > 150 && android.graphics.Color.red(pixel) < 40
                } finally { bitmap.recycle() }
            }
            if (InstrumentationRegistry.getArguments().getString("captureUi") == "true") {
                val screenshot = requireNotNull(inst.uiAutomation.takeScreenshot())
                try { File(ctx.cacheDir, "video-preview.png").outputStream().use {
                    assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { screenshot.recycle() }
            }
            val position = main { player.currentPosition }
            compose.onNodeWithTag("video_close").performClick()
            compose.onNodeWithTag("video_player").assertDoesNotExist()
            await { main { player.currentPosition > position + 150 && player.isPlaying } }
            assertEquals("owned-video", main { player.currentMediaItem?.mediaId })
            assertEquals(1, main { player.mediaItemCount })
            main { connection.disconnect(); connection.connect() }
            await { connection.player.value != null }
            assertSame(player, connection.player.value)
            main { player.pause() }
            // Playback-thread acknowledgements can settle the reported clock by a few
            // milliseconds after pause returns. Require it to settle, then stay still.
            await {
                val before = main { player.currentPosition }
                SystemClock.sleep(50)
                main { !player.isPlaying && !player.playWhenReady && player.currentPosition == before }
            }
            val paused = main { player.currentPosition }
            SystemClock.sleep(250)
            assertEquals(paused, main { player.currentPosition })
        } finally {
            main {
                open = false
                player.removeListener(listener)
                player.pause()
                player.volume = oldVolume
                if (oldItems.isEmpty()) player.clearMediaItems()
                else player.setMediaItems(oldItems, oldIndex.coerceIn(oldItems.indices), oldPosition)
                if (oldState == Player.STATE_READY || oldState == Player.STATE_BUFFERING) player.prepare()
                player.playWhenReady = oldPlaying
                connection.disconnect()
                host.finish()
                val edit = prefs.edit()
                saved.forEach { (key, value) ->
                    edit.remove(key)
                    when(value) {
                        is String -> edit.putString(key, value)
                        is Long -> edit.putLong(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                    }
                }
                assertTrue(edit.commit())
            }
            assertTrue(file.delete())
        }
    }
}
