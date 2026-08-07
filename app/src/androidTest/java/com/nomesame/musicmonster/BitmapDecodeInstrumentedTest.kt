package com.nomesame.musicmonster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.ui.theme.PaletteEngine
import com.nomesame.musicmonster.ui.theme.decodeSampledBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The custom-background decoder against real files and a real BitmapFactory.
 * A JVM test can only re-check the arithmetic here (that is what
 * `PaletteSampleTest` does); whether a truncated JPEG returns null or throws,
 * and whether a 12000x12000 image fits in the heap, are answers only the
 * platform can give — and they are exactly the answers that differed per
 * device in this project's history (the JPEG-decode bug, the OOM on large
 * backgrounds).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BitmapDecodeInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tempUri(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun imageBytes(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Deliberately NOT the app's orange accent: androidx.palette's default
        // filter discards hues 10-37 at s<=0.82 as skin tones, so a solid
        // orange image yields no swatch at all and would make the palette
        // assertion below test the filter instead of our code.
        Canvas(bitmap).drawColor(Color.rgb(0x3C, 0x8B, 0xE5))
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(format, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun decodesJpegBackground() {
        val uri = tempUri("ok.jpg", imageBytes(400, 300, Bitmap.CompressFormat.JPEG))
        val bitmap = decodeSampledBitmap(context, uri, 256)
        assertNotNull("a valid JPEG background must decode", bitmap)
        assertTrue("longest edge must be downsampled to ~256", bitmap!!.width <= 512)
        bitmap.recycle()
    }

    @Test
    fun decodesPngBackground() {
        val uri = tempUri("ok.png", imageBytes(300, 400, Bitmap.CompressFormat.PNG))
        val bitmap = decodeSampledBitmap(context, uri, 256)
        assertNotNull("a valid PNG background must decode", bitmap)
        bitmap!!.recycle()
    }

    @Test
    fun truncatedImageReturnsNullInsteadOfThrowing() {
        val full = imageBytes(800, 600, Bitmap.CompressFormat.JPEG)
        // Header survives, pixel data does not — the shape of a half-copied or
        // half-synced file, which is what a cloud-backed picker hands over.
        val uri = tempUri("truncated.jpg", full.copyOf(full.size / 4))
        // Must not throw. Either outcome (null or a partial bitmap) is fine;
        // crashing is not.
        decodeSampledBitmap(context, uri, 256)?.recycle()
    }

    @Test
    fun emptyFileReturnsNull() {
        val uri = tempUri("empty.jpg", ByteArray(0))
        assertNull(decodeSampledBitmap(context, uri, 256))
    }

    @Test
    fun nonImageFileReturnsNull() {
        val uri = tempUri("notanimage.jpg", "this is plain text, not an image".toByteArray())
        assertNull(decodeSampledBitmap(context, uri, 256))
    }

    @Test
    fun missingFileReturnsNull() {
        val uri = Uri.fromFile(File(context.cacheDir, "does_not_exist_${System.nanoTime()}.jpg"))
        assertNull(decodeSampledBitmap(context, uri, 256))
    }

    @Test
    fun unreadableContentUriReturnsNull() {
        // A `content://` authority nobody serves: what a restored backup or a
        // revoked SAF grant leaves behind in prefs.
        val uri = Uri.parse("content://com.example.gone/image/1")
        assertNull(decodeSampledBitmap(context, uri, 256))
    }

    @Test
    fun veryLargeImageDoesNotExhaustMemory() {
        // ~48 MB as ARGB_8888 if decoded at full size; the subsampling pass is
        // the only reason this fits. Decoding it whole is what used to OOM on
        // low-heap devices.
        val uri = tempUri("huge.jpg", imageBytes(3500, 3500, Bitmap.CompressFormat.JPEG))
        val bitmap = decodeSampledBitmap(context, uri, 256)
        assertNotNull("large background must still decode, downsampled", bitmap)
        assertTrue(
            "decoded ${bitmap!!.width}x${bitmap.height}: subsampling did not apply",
            bitmap.width <= 512 && bitmap.height <= 512
        )
        bitmap.recycle()
    }

    @Test
    fun extremeAspectRatioDecodes() {
        val uri = tempUri("panorama.jpg", imageBytes(4000, 8, Bitmap.CompressFormat.JPEG))
        val bitmap = decodeSampledBitmap(context, uri, 256)
        assertNotNull("a 4000x8 panorama must not be rejected", bitmap)
        assertTrue("degenerate edge must stay at least 1px", bitmap!!.height >= 1)
        bitmap.recycle()
    }

    @Test
    fun exifRotatedImageComesBackUpright() {
        // A 400x200 landscape JPEG tagged ORIENTATION_ROTATE_90 is, to the
        // user, a 200x400 portrait image. BitmapFactory ignores the tag, so
        // without the rotation step the background lands sideways — which is
        // exactly what a photo straight from a phone camera looks like.
        val file = File(context.cacheDir, "exif_rotated.jpg")
        file.writeBytes(imageBytes(400, 200, Bitmap.CompressFormat.JPEG))
        android.media.ExifInterface(file.absolutePath).apply {
            setAttribute(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_ROTATE_90.toString()
            )
            saveAttributes()
        }
        val bitmap = decodeSampledBitmap(context, Uri.fromFile(file), 512)
        assertNotNull(bitmap)
        assertTrue(
            "EXIF orientation ignored: got ${bitmap!!.width}x${bitmap.height}, " +
                "expected a portrait bitmap",
            bitmap.height > bitmap.width
        )
        bitmap.recycle()
    }

    @Test
    fun imageWithoutExifIsUnchanged() {
        val uri = tempUri("no_exif.png", imageBytes(400, 200, Bitmap.CompressFormat.PNG))
        val bitmap = decodeSampledBitmap(context, uri, 512)
        assertNotNull(bitmap)
        assertTrue("a landscape image must stay landscape", bitmap!!.width > bitmap.height)
        bitmap.recycle()
    }

    @Test
    fun paletteNeverThrowsForAnyInput() = runBlocking {
        val engine = PaletteEngine(context)
        val uris = listOf(
            tempUri("p_ok.jpg", imageBytes(200, 200, Bitmap.CompressFormat.JPEG)),
            tempUri("p_empty.jpg", ByteArray(0)),
            tempUri("p_text.jpg", "nope".toByteArray()),
            Uri.parse("content://com.example.gone/image/1"),
            Uri.EMPTY,
        )
        // Every one of these must return (null or a color), never throw: this
        // runs on the UI's coroutine right after the user picks a background.
        val results = uris.map { engine.accentFrom(it) }
        assertEquals(uris.size, results.size)
        assertNotNull("a valid image must still yield an accent", results.first())
    }
}
