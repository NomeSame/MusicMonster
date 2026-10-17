package com.nomesame.musicmonster

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CatalogRefreshTest {
    private fun field(service: MusicService, name: String) = MusicService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("file:///fixture-$id.wav").build()
    private fun apply(service: MusicService, items: List<MediaItem>) {
        MusicService::class.java.getDeclaredMethod("applyLibrary", Triple::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(service, Triple(items, items.map { "Catalog ${it.mediaId}" }, emptyMap<String, Long>()), false, true)
    }
    private fun withService(test: (MusicService, ExoPlayer) -> Unit) {
        val lifecycle = Robolectric.buildService(MusicService::class.java).create()
        val service = lifecycle.get()
        try {
            (field(service, "loadExecutor").get(service) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            test(service, field(service, "player").get(service) as ExoPlayer)
        } finally { lifecycle.destroy() }
    }
    @Test fun catalogRefreshPreservesPlaylistDuplicatesIndexTitlesPositionAndShuffle() = withService { service, player ->
        player.setMediaItems(listOf(item("a"), item("b"), item("a")), 2, 350)
        player.shuffleModeEnabled = true
        player.playWhenReady = false
        field(service, "titles").set(service, listOf("Playlist A", "Playlist B", "Playlist A"))
        val timeline = player.currentTimeline
        apply(service, listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf("a", "b", "a"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        assertEquals(listOf("Playlist A", "Playlist B", "Playlist A"), field(service, "titles").get(service))
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(350, player.currentPosition.toInt())
        assertTrue(player.shuffleModeEnabled)
        assertFalse(player.playWhenReady)
        assertEquals(timeline, player.currentTimeline)
    }
    @Test fun emptyQueueStaysEmptyUntilExplicitPlaySelectsNewCatalogItem() = withService { service, player ->
        player.clearMediaItems()
        apply(service, listOf(item("new")))
        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
        MusicService::class.java.getDeclaredMethod("startPlayback", String::class.java).apply { isAccessible = true }
            .invoke(service, "new")
        assertEquals(1, player.mediaItemCount)
        assertEquals("new", player.currentMediaItem?.mediaId)
        assertTrue(player.playWhenReady)
    }
}
