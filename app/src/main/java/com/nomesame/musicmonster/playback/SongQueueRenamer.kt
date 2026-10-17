package com.nomesame.musicmonster.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.nomesame.musicmonster.Song

/** Replace every occurrence while retaining the current track, position and exact shuffle traversal. */
@OptIn(UnstableApi::class)
object SongQueueRenamer {
    fun apply(player: Player, oldId: String, song: Song) {
        val matches = (0 until player.mediaItemCount).filter { player.getMediaItemAt(it).mediaId == oldId }
        if (matches.isEmpty()) return
        val replacesCurrentSource = player.currentMediaItem?.mediaId == oldId &&
            player.currentMediaItem?.localConfiguration?.uri != song.uri
        if (replacesCurrentSource) {
            // A load of the old URI can fail between the filesystem rename and source replacement.
            // Recover once using the new URI; a genuine error in the new source remains visible.
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    if (player.currentMediaItem?.mediaId == song.id &&
                        player.currentMediaItem?.localConfiguration?.uri == song.uri) player.prepare()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) player.removeListener(this)
                }
            })
        }
        val current = player.currentMediaItemIndex
        val position = player.currentPosition
        val timeline = player.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < player.mediaItemCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        matches.forEach { itemIndex ->
            val item = player.getMediaItemAt(itemIndex)
            player.replaceMediaItem(itemIndex, item.buildUpon().setMediaId(song.id).setUri(song.uri)
                .setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(song.title).build()).build())
        }
        // ExoPlayer may advance to a different item when the current source is replaced.
        if (current in 0 until player.mediaItemCount) player.seekTo(current, position)
        if (player is ExoPlayer && order.size == player.mediaItemCount) {
            player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), 0L))
        }
        if (replacesCurrentSource) player.prepare()
    }
}
