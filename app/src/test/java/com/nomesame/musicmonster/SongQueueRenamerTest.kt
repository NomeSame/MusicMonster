package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.playback.SongQueueRenamer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for ExoPlayer advancing the current index when a source is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongQueueRenamerTest {
    private fun order(player: Player): List<Int> {
        val timeline = player.currentTimeline
        val result = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && result.size < player.mediaItemCount) {
            result += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return result
    }

    @Test fun changingCurrentAndDuplicateUrisPreservesPositionAndExactShuffleTraversal() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            player.setMediaItems(listOf("old", "other", "old").map {
                MediaItem.Builder().setMediaId(it).setUri("content://fixture/$it").build()
            }, 2, 3456)
            player.shuffleModeEnabled = true
            player.playWhenReady = true
            val traversal = order(player)
            val renamed = Song("new", "Renamed", Uri.parse("content://fixture/new"), 10000)
            SongQueueRenamer.apply(player, "old", renamed)
            assertEquals(2, player.currentMediaItemIndex)
            assertEquals(3456L, player.currentPosition)
            assertTrue(player.playWhenReady)
            assertTrue(player.shuffleModeEnabled)
            assertEquals(traversal, order(player))
            assertEquals(listOf("new", "other", "new"), (0..2).map { player.getMediaItemAt(it).mediaId })
            assertEquals(renamed.uri, player.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals("Renamed", player.currentMediaItem!!.mediaMetadata.title)
        } finally { player.release() }
    }

    @Test fun stableIdsAndUnrelatedRenameKeepTheSameQueueAndPausedState() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            val song = Song("42", "New title", Uri.parse("content://media/external/audio/media/42"), 10000)
            player.setMediaItem(MediaItem.Builder().setMediaId(song.id).setUri(song.uri).build(), 1200)
            SongQueueRenamer.apply(player, song.id, song)
            assertEquals(1200L, player.currentPosition)
            assertEquals(1, player.mediaItemCount)
            assertFalse(player.playWhenReady)
            SongQueueRenamer.apply(player, "unrelated", song.copy(id = "other"))
            assertEquals("42", player.currentMediaItem!!.mediaId)
            assertEquals(1200L, player.currentPosition)
        } finally { player.release() }
    }
}
