package com.nomesame.musicmonster

import android.net.Uri

/**
 * Simple data class representing a song.
 */
data class Song(
    /**
     * The unique identifier from {@link MediaStore.Audio.Media#_ID}.  This value is used as the mediaId
     * when calling {@code controller.transportControls.playFromMediaId(id, null)}.
     */
    val id: String,
    /**
     * Human readable title of the song.
     */
    val title: String,
    /**
     * The content URI pointing to the audio file.  It is not used directly by the service but kept for
     * reference if needed.
     */
    val uri: Uri,
    /**
     * Track duration in milliseconds, as reported by MediaStore.
     */
    val durationMs: Long
)

