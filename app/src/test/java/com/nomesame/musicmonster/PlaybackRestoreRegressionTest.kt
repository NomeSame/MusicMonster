package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PlaybackRestoreRegressionTest {
    @Test fun explicitPlayCanSelectAnImportWhenThePreviousQueueWasEmpty() {
        val lifecycle = Robolectric.buildService(MusicService::class.java).create()
        val service = lifecycle.get()
        try {
            (field(service, "loadExecutor") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val player = field(service, "player") as ExoPlayer
            player.clearMediaItems()
            val imported = MediaItem.Builder().setMediaId("new-import")
                .setUri("file:///isolated-import.wav").build()
            MusicService::class.java.getDeclaredMethod("applyLibrary", Triple::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(service, Triple(listOf(imported), listOf("Imported"), emptyMap<String, Long>()), false, true)
            assertEquals(0, player.mediaItemCount)
            MusicService::class.java.getDeclaredMethod("startPlayback", String::class.java)
                .apply { isAccessible = true }.invoke(service, "new-import")
            assertEquals("new-import", player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
        } finally { lifecycle.destroy() }
    }

    @Test fun startupKeepsSavedSongPositionAndShuffleBeforePlayerCallbacksRun() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        val lifecycle = Robolectric.buildService(MusicService::class.java).create()
        val service = lifecycle.get()
        try {
            val scanner = field(service, "loadExecutor") as ExecutorService
            scanner.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            prefs.edit().putString(MusicService.PREF_LAST_SONG_ID, "second")
                .putLong(MusicService.PREF_LAST_POSITION, 42000)
                .putBoolean(MusicService.PREF_SHUFFLE_ENABLED, true).commit()
            val items = listOf("first", "second").map {
                MediaItem.Builder().setMediaId(it).setUri("file:///restore-fixture-$it.wav").build()
            }
            MusicService::class.java.getDeclaredMethod("applyLibrary", Triple::class.java,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(service, Triple(items, listOf("First", "Second"), emptyMap<String, Long>()), true)
            val player = field(service, "player") as ExoPlayer
            assertEquals("second", player.currentMediaItem!!.mediaId)
            assertEquals(42000L, player.currentPosition)
            assertTrue(player.shuffleModeEnabled)
            assertFalse(player.playWhenReady)
        } finally { lifecycle.destroy() }
    }

    @Test fun emptyStartupAndEarlyServiceDestructionMustNotEraseSavedSession() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(MusicService.PREF_LAST_SONG_ID, "saved")
            .putLong(MusicService.PREF_LAST_POSITION, 123456).commit()
        val lifecycle = Robolectric.buildService(MusicService::class.java).create()
        lifecycle.destroy()
        assertEquals("saved", prefs.getString(MusicService.PREF_LAST_SONG_ID, null))
        assertEquals(123456L, prefs.getLong(MusicService.PREF_LAST_POSITION, 0))
    }

    private fun field(service: MusicService, name: String): Any? =
        MusicService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
}
