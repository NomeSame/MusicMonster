package com.nomesame.musicmonster.model

/** Immutable checkpoint captured on the player thread and written on an I/O worker. */
data class PlaybackResumeState(val songId: String?, val positionMs: Long, val shuffleEnabled: Boolean)
