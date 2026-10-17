package com.nomesame.musicmonster.playback

import androidx.media3.common.Player
import com.nomesame.musicmonster.SeekStep

/** Relative seek against the engine's live position; never changes the playlist item. */
fun seekWithinSong(player: Player, seconds: Int, forward: Boolean): Boolean {
    if (player.currentMediaItem == null || !player.isCurrentMediaItemSeekable) return false
    val target = SeekStep.target(player.currentPosition, player.duration, seconds, forward) ?: return false
    player.seekTo(target)
    return true
}
