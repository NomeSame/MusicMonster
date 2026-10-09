package com.nomesame.musicmonster.model

/** App-design fallback for media surfaces, not song-specific album artwork. */
data class MediaAppearance(
    val backgroundEnabled: Boolean,
    val backgroundUri: String?,
    val scrim: Float,
    val accent: Int,
    val crop: ArtworkCrop = ArtworkCrop.defaultForBackground(backgroundUri != null)
) {
    /** Accent is a notification hint, not a reason to decode the image again. */
    val imageKey: ImageKey
        get() = ImageKey(backgroundEnabled, backgroundUri, scrim, crop)

    data class ImageKey(val enabled: Boolean, val uri: String?, val scrim: Float, val crop: ArtworkCrop)
}
