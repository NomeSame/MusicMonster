package com.nomesame.musicmonster.model

/** Normalized travel within the available square crop; independent of image dimensions. */
data class ArtworkCrop(val x: Float = 0.5f, val y: Float = 0.5f, val zoom: Float = 1f) {
    companion object {
        /** The bundled portrait starts on its top motif; custom images start centered. */
        fun defaultForBackground(hasCustomBackground: Boolean): ArtworkCrop =
            if (hasCustomBackground) ArtworkCrop() else ArtworkCrop(y = 0f, zoom = 2f)
    }
}
data class ArtworkCropRect(val left: Int, val top: Int, val side: Int)