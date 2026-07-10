package com.nomesame.musicmonster.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a matching accent color from a user-chosen background image using
 * androidx.palette. System/hardware access (bitmap decode) is encapsulated here
 * per ARCHITECTURE.md — the UI just receives a [Color].
 */
class PaletteEngine(private val context: Context) {

    /**
     * Returns a vibrant accent color derived from the image at [uri], or null if
     * the image can't be read or no usable swatch is found. Runs off the main
     * thread. Never throws — failures return null.
     */
    suspend fun accentFrom(uri: Uri): Color? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeSampledBitmap(context, uri, PALETTE_SAMPLE_DIM)
                ?: return@runCatching null
            val palette = Palette.from(bitmap).generate()
            bitmap.recycle()
            val rgb = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: return@runCatching null
            Color(rgb)
        }.getOrNull()
    }

    private companion object {
        const val PALETTE_SAMPLE_DIM = 256
    }
}

/**
 * Decodes the image at [uri] downscaled so its longest edge is roughly [maxDim]
 * pixels (two-pass BitmapFactory). Shared by [PaletteEngine] and the background
 * renderer. Returns null on any failure. Works on all supported API levels.
 */
internal fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    val resolver = context.contentResolver
    // Read once into a byte array — BitmapFactory.decodeStream relies on
    // mark/reset which some ContentProvider streams don't support, causing
    // decode to return null silently for JPEG etc. ByteArray decoding is
    // safe and portable across all providers.
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val (w, h) = bounds.outWidth to bounds.outHeight
    if (w <= 0 || h <= 0) return null

    val opts = BitmapFactory.Options().apply { inSampleSize = computeInSampleSize(w, h, maxDim) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/**
 * Power-of-two subsampling factor so the image's longest edge is downscaled to
 * about [maxDim] px or less. Pure integer math — unit-tested on the JVM.
 */
internal fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
    if (maxDim <= 0) return 1
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / sample > maxDim) sample *= 2
    return sample
}
