package com.nomesame.musicmonster

import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.model.ArtworkCropRect

/** Shared by the service renderer and UI preview: always a bounded square, never stretched. */
object ArtworkCropGeometry {
    fun normalized(crop: ArtworkCrop) = ArtworkCrop(unit(crop.x), unit(crop.y),
        if (crop.zoom.isFinite()) crop.zoom.coerceIn(1f, 4f) else 1f)

    fun rectangle(width: Int, height: Int, crop: ArtworkCrop): ArtworkCropRect {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val position = normalized(crop)
        val side = (minOf(width, height) / position.zoom).toInt().coerceAtLeast(1)
        return ArtworkCropRect(((width - side).toDouble() * position.x).toInt(),
            ((height - side).toDouble() * position.y).toInt(), side)
    }

    fun drag(crop: ArtworkCrop, dx: Float, dy: Float, viewSide: Float,
             width: Int, height: Int): ArtworkCrop {
        if (viewSide <= 0f || !viewSide.isFinite() || width <= 0 || height <= 0) return normalized(crop)
        val side = rectangle(width, height, crop).side.toFloat()
        val overflowX = viewSide * (width / side - 1f)
        val overflowY = viewSide * (height / side - 1f)
        return normalized(ArtworkCrop(
            if (overflowX > 0f && dx.isFinite()) crop.x - dx / overflowX else crop.x,
            if (overflowY > 0f && dy.isFinite()) crop.y - dy / overflowY else crop.y, crop.zoom))
    }

    private fun unit(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f
}
