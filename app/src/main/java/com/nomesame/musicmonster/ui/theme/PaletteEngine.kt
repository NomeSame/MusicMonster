package com.nomesame.musicmonster.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
            // finally, not a trailing call: if generate() throws (it does on
            // some malformed images) the bitmap's native memory would never be
            // freed, and background pickers are exactly where big bitmaps live.
            val palette = try {
                Palette.from(bitmap).generate()
            } finally {
                bitmap.recycle()
            }
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
    // Two independent streams, one per decode pass. Each openInputStream() yields
    // a fresh, position-0 stream, so the inJustDecodeBounds pass and the sampled
    // pass never rely on mark/reset (which many ContentProvider streams lack).
    // This also avoids materializing the entire file in a heap byte array — the
    // previous whole-file readBytes() could transiently OOM low-power devices
    // when a very large/corrupt image was picked as the background.
    // Bounds pass: inJustDecodeBounds makes decodeStream return null by design
    // (the dimensions land in `opts`), so success is detected via opts.out*.
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsValid = runCatching {
        resolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, boundsOpts) }
        boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0
    }.getOrDefault(false)
    if (!boundsValid) return null

    val sampleOpts = BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxDim)
    }
    val decoded = runCatching {
        resolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, sampleOpts) }
    }.getOrNull() ?: return null
    return applyExifRotation(context, uri, decoded)
}

/**
 * Rotates [bitmap] according to the image's EXIF orientation tag.
 *
 * BitmapFactory ignores that tag. Phone cameras habitually store the sensor
 * image unrotated and record the orientation in EXIF instead, so a background
 * picked straight out of the camera roll lands sideways or upside down —
 * on whichever devices/camera apps write it that way, which is why it looks
 * like it "only happens on some phones". Returns the input unchanged when
 * there is nothing to rotate or the tag can't be read.
 */
private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (degrees == 0f) return bitmap
    return runCatching {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        // createBitmap can hand back the same instance when nothing changed;
        // recycling it then would hand the caller a dead bitmap.
        if (rotated !== bitmap) bitmap.recycle()
        rotated
    }.getOrDefault(bitmap)
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
