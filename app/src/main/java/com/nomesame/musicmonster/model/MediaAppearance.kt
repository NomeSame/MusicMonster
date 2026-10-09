package com.nomesame.musicmonster.model

/** App-design fallback for media surfaces, not song-specific album artwork. */
data class MediaAppearance(
    val backgroundEnabled: Boolean,
    val backgroundUri: String?,
    val scrim: Float,
    val accent: Int
) {
    /** Accent is a notification hint, not a reason to decode the image again. */
    val imageKey: Triple<Boolean, String?, Float>
        get() = Triple(backgroundEnabled, backgroundUri, scrim)
}
