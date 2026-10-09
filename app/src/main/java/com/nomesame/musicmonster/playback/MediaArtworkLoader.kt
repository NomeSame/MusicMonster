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
import com.nomesame.musicmonster.model.MediaAppearance
import com.nomesame.musicmonster.ui.theme.computeInSampleSize
import com.nomesame.musicmonster.ui.theme.decodeSampledBitmap

/** Bounded offline artwork. Only called on the artwork worker, never the UI thread. */
class MediaArtworkLoader(private val context: Context) {
    fun load(appearance: MediaAppearance): Bitmap? {
        if (!appearance.backgroundEnabled) return null
        return runCatching {
            val custom = appearance.backgroundUri?.let {
                decodeSampledBitmap(context, Uri.parse(it), ARTWORK_SIZE)
            }
            val source = custom ?: loadDefault() ?: return@runCatching null
            try {
                render(source, appearance.scrim)
            } finally {
                source.recycle()
            }
        }.getOrNull()
    }

    private fun loadDefault(): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(context.resources, R.drawable.background_screen2, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, ARTWORK_SIZE)
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.background_screen2, options)
    }

    companion object {
        // 256 KiB per ARGB bitmap: leaves ample Binder budget for notification/public version.
        const val ARTWORK_SIZE = 256

        internal fun render(source: Bitmap, scrim: Float): Bitmap {
            val result = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
            val side = minOf(source.width, source.height)
            val left = (source.width - side) / 2
            val top = (source.height - side) / 2
            val canvas = Canvas(result)
            canvas.drawBitmap(source, Rect(left, top, left + side, top + side),
                Rect(0, 0, ARTWORK_SIZE, ARTWORK_SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
            val dim = if (scrim.isFinite()) scrim.coerceIn(0f, 1f) else 0.75f
            canvas.drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
            return result
        }
    }
}
