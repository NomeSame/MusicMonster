package com.nomesame.musicmonster.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.ArtworkCropGeometry
import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.model.MediaAppearance
import com.nomesame.musicmonster.ui.theme.computeInSampleSize
import com.nomesame.musicmonster.ui.theme.decodeSampledBitmap

/** Bounded offline artwork. Only called on the artwork worker, never the UI thread. */
class MediaArtworkLoader(private val context: Context) {
    fun load(appearance: MediaAppearance): Bitmap? {
        if (!appearance.backgroundEnabled) return null
        return runCatching {
            val source = loadPreview(appearance,
                (ARTWORK_SIZE * ArtworkCropGeometry.normalized(appearance.crop).zoom).toInt()) ?: return@runCatching null
            try {
                render(source, appearance.scrim, appearance.crop)
            } finally {
                source.recycle()
            }
        }.getOrNull()
    }

    /** Uncropped current background for the editor; caller must run off the main thread. */
    fun loadPreview(appearance: MediaAppearance, maxDim: Int = 1024): Bitmap? {
        if (!appearance.backgroundEnabled) return null
        return runCatching {
            appearance.backgroundUri?.let { decodeSampledBitmap(context, Uri.parse(it), maxDim) }
                ?: loadDefault(maxDim)
        }.getOrNull()
    }

    private fun loadDefault(maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(context.resources, R.drawable.background_screen2, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.background_screen2, options)
    }

    companion object {
        // 256 KiB per ARGB bitmap: leaves ample Binder budget for notification/public version.
        const val ARTWORK_SIZE = 256

        internal fun render(source: Bitmap, scrim: Float, crop: ArtworkCrop = ArtworkCrop()): Bitmap {
            val result = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
            val (left, top, side) = ArtworkCropGeometry.rectangle(source.width, source.height, crop)
            val canvas = Canvas(result)
            canvas.drawBitmap(source, Rect(left, top, left + side, top + side),
                Rect(0, 0, ARTWORK_SIZE, ARTWORK_SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
            val dim = if (scrim.isFinite()) scrim.coerceIn(0f, 1f) else 0.75f
            canvas.drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
            return result
        }
    }
}
