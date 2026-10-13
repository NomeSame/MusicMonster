package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import com.nomesame.musicmonster.model.PlaybackResumeState

interface PlaybackStateStore {
    fun load(): PlaybackResumeState
    fun save(state: PlaybackResumeState): Boolean
}

/** Keeps the existing preference keys; save must run on an I/O worker. */
class PlaybackStateRepository(private val prefs: SharedPreferences) : PlaybackStateStore {
    override fun load(): PlaybackResumeState = PlaybackResumeState(
        prefs.stringOr(KEY_SONG, null)?.takeIf { it.isNotBlank() },
        prefs.longOr(KEY_POSITION, 0L).coerceAtLeast(0L),
        prefs.booleanOr(KEY_SHUFFLE, false)
    )

    /** A confirmed atomic disk write, not just an in-memory apply(). */
    override fun save(state: PlaybackResumeState): Boolean {
        if (state.songId.isNullOrBlank()) return false
        return prefs.edit().putString(KEY_SONG, state.songId)
            .putLong(KEY_POSITION, state.positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, state.shuffleEnabled).commit()
    }

    companion object {
        const val KEY_SONG = "last_song_id"
        const val KEY_POSITION = "last_position"
        const val KEY_SHUFFLE = "shuffle_enabled"
    }
}
