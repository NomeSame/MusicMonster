package com.nomesame.musicmonster

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real player with duplicate playlist occurrences; no user service/session/preferences. */
@RunWith(AndroidJUnit4::class)
class ImportQueuePreservationTest {
    @Test fun catalogueRefreshNeverReplacesTheLiveQueueOrItsTitles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try {
                val first = MediaItem.Builder().setMediaId("playlist-a").setUri(Uri.EMPTY).build()
                val second = first.buildUpon().setMediaId("playlist-b").build()
                player.setMediaItems(listOf(first, second, first), 2, 4321)
                player.shuffleModeEnabled = true
                player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                player.playWhenReady = true
                // Exercise the production catalogue branch without starting the user's service.
                val service = MusicService()
                MusicService::class.java.getDeclaredField("player").apply { isAccessible = true }.set(service, player)
                val titles = MusicService::class.java.getDeclaredField("titles").apply { isAccessible = true }
                titles.set(service, listOf("A", "B", "A duplicate"))
                val imported = first.buildUpon().setMediaId("new-import").build()
                val method = MusicService::class.java.getDeclaredMethod("applyLibrary",
                    Triple::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                method.invoke(service, Triple(listOf(first, imported), listOf("A", "New"), emptyMap<String, Long>()), false, true)
                assertEquals(listOf("playlist-a", "playlist-b", "playlist-a"),
                    (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                assertEquals(2, player.currentMediaItemIndex)
                assertEquals(4321L, player.currentPosition)
                assertTrue(player.playWhenReady)
                assertTrue(player.shuffleModeEnabled)
                assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, player.repeatMode)
                assertEquals(listOf("A", "B", "A duplicate"), titles.get(service))
                val catalogue = MusicService::class.java.getDeclaredField("libraryItems").apply { isAccessible = true }
                assertTrue((catalogue.get(service) as Map<*, *>).containsKey("new-import"))
            } finally { player.release() }
        }
    }
}
