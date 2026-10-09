package com.nomesame.musicmonster

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.lifecycle.ViewModelStore
import androidx.core.graphics.ColorUtils
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.playback.MediaArtworkLoader
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real framework/Binder publication; only appearance keys are temporarily changed/restored. */
@RunWith(AndroidJUnit4::class)
class MediaArtworkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var prefs: SharedPreferences
    private var originals: Map<String, Any?> = emptyMap()
    private lateinit var vm: MainViewModel
    private lateinit var controller: MediaControllerCompat
    private val store = ViewModelStore()
    private val files = mutableListOf<File>()
    private val keys = listOf(MediaAppearanceRepository.KEY_ACCENT, BackgroundRepository.KEY_ENABLED,
        BackgroundRepository.KEY_URI, BackgroundRepository.KEY_SCRIM,
        MediaAppearanceRepository.KEY_CROP_X, MediaAppearanceRepository.KEY_CROP_Y,
        MediaAppearanceRepository.KEY_CROP_ZOOM)

    @Before fun setup() {
        // Artwork publication must also work while ordinary notification permission is denied.
        prefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        originals = keys.associateWith { prefs.all[it] }
        val intent = Intent(context, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        await { MusicService.sessionToken != null && notification() != null }
        main {
            vm = MainViewModel(context.applicationContext as Application)
            store.put("artwork_fixture", vm)
            controller = MediaControllerCompat(context, MusicService.sessionToken!!)
            vm.artworkCrop.setPosition(ArtworkCrop())
            vm.resetCustomBackground()
            vm.setCustomBgEnabled(true)
            vm.setCustomBgScrim(0f)
        }
    }

    @After fun cleanup() {
        if (::prefs.isInitialized) {
            // Preserve missing and corrupt original types; never clear unrelated user preferences.
            val editor = prefs.edit()
            originals.forEach { (key, value) ->
                editor.remove(key)
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            main { editor.commit(); store.clear() }
        }
        files.forEach { assertTrue("Fixture was not removed", it.delete()) }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Media artwork/notification state not reached before deadline")
    }

    private fun notification(): Notification? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == MusicService.NOTIFICATION_ID }?.notification

    private fun artwork(): Bitmap? = controller.metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)

    private fun cardColor(card: Notification?): Int? {
        val image = (card?.getLargeIcon()?.loadDrawable(context) as? BitmapDrawable)?.bitmap ?: return null
        return image.getPixel(image.width / 2, image.height / 2)
    }

    private fun awaitColor(color: Int) = await {
        val card = notification()
        artwork()?.getPixel(128, 128) == color && cardColor(card) == color && cardColor(card?.publicVersion) == color
    }

    private fun fixture(color: Int): Uri {
        val file = File.createTempFile("artwork-fixture-", ".png", context.cacheDir)
        files.add(file)
        val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        image.eraseColor(color)
        try { file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        return Uri.fromFile(file)
    }

    @Test fun bundledDefaultIsPublishedAsBoundedArtworkOnSessionAndBothCards() {
        val expected = MediaArtworkLoader(context).load(MediaAppearanceRepository(prefs).load())
            ?: throw AssertionError("Bundled default failed to decode")
        try {
            await { expected.sameAs(artwork()) && notification()?.getLargeIcon() != null }
            assertTrue(expected.sameAs(artwork()))
            val art = artwork()!!
            assertEquals(256, art.width)
            assertEquals(256, art.height)
            assertTrue(art.allocationByteCount <= 256 * 256 * 4)
            assertNotNull(notification()!!.publicVersion.getLargeIcon())
        } finally { expected.recycle() }
    }

    @Test fun customBackgroundColorAndDimChangesUpdateWithoutReplacingSession() {
        val token = MusicService.sessionToken
        main { vm.onCustomBackgroundPicked(fixture(Color.RED)) }
        awaitColor(Color.RED)
        main { vm.setAccentColor(androidx.compose.ui.graphics.Color(Color.GREEN)) }
        await { notification()?.color == Color.GREEN && notification()?.publicVersion?.color == Color.GREEN }
        assertEquals(Color.RED, artwork()!!.getPixel(128, 128))
        main { vm.setAccentColor(androidx.compose.ui.graphics.Color(Color.BLACK)) }
        await { notification()?.color == Color.BLACK }
        val renderedNotification = notification()!!.contentView.apply(context, null)
        val textColor = renderedNotification.findViewById<TextView>(R.id.notif_title).currentTextColor
        val panelColor = (renderedNotification.background as ColorDrawable).color
        assertTrue("Custom-card captions must stay readable for a dark accent",
            ColorUtils.calculateContrast(textColor, panelColor) >= 4.5)
        main { vm.onCustomBackgroundPicked(fixture(Color.BLUE)) }
        awaitColor(Color.BLUE)
        main { vm.setCustomBgScrim(1f) }
        awaitColor(Color.BLACK)
        assertEquals(token, MusicService.sessionToken)
        assertNotNull(notification()!!.contentIntent)
        assertEquals(notification()!!.contentIntent, controller.sessionActivity)
    }

    @Test fun disablingRemovesArtworkAndResetRestoresBundledDefault() {
        main { vm.onCustomBackgroundPicked(fixture(Color.RED)) }
        awaitColor(Color.RED)
        main { vm.setCustomBgEnabled(false) }
        await { artwork() == null && notification()?.getLargeIcon() == null &&
            notification()?.publicVersion?.getLargeIcon() == null }
        main { vm.resetCustomBackground(); vm.setCustomBgEnabled(true) }
        await { artwork() != null && artwork()?.getPixel(128, 128) != Color.RED }
        assertNull(BackgroundRepository(prefs).uri())
    }

    @Test fun unreadableCustomImageFallsBackToDefaultAndDisabledLoaderReturnsNull() {
        val original = MediaAppearanceRepository(prefs).load()
        val loader = MediaArtworkLoader(context)
        val expected = loader.load(original) ?: throw AssertionError("Default image missing")
        val fallback = loader.load(original.copy(backgroundUri = "file:///nonexistent/artwork.png"))
            ?: throw AssertionError("Missing-image fallback failed")
        try {
            assertTrue(expected.sameAs(fallback))
            assertNull(loader.load(original.copy(backgroundEnabled = false)))
        } finally { expected.recycle(); fallback.recycle() }
        main {
            prefs.edit().putString(BackgroundRepository.KEY_URI, "file:///nonexistent/artwork.png").commit()
        }
        val fresh = loader.load(original) ?: throw AssertionError("Default missing")
        try { await { artwork()?.sameAs(fresh) == true } } finally { fresh.recycle() }
    }

    @Test fun rendererBoundsExtremeAspectRatiosAndClampsInvalidDimValues() {
        val source = Bitmap.createBitmap(2048, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            listOf(-1f, 0f, 1f, 2f, Float.NaN).forEach { dim ->
                val rendered = MediaArtworkLoader.render(source, dim)
                try {
                    assertEquals(256, rendered.width)
                    assertEquals(256, rendered.height)
                    val red = Color.red(rendered.getPixel(128, 128))
                    when {
                        !dim.isFinite() -> assertEquals(64f, red.toFloat(), 1f)
                        dim <= 0f -> assertEquals(255, red)
                        else -> assertEquals(0, red)
                    }
                    assertFalse(source.isRecycled)
                } finally { rendered.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun rapidAccentBurstStillPublishesTheFinalColorToBothCards() {
        val token = MusicService.sessionToken
        main {
            repeat(40) { index ->
                vm.setAccentColor(androidx.compose.ui.graphics.Color(Color.rgb(index * 5, 20, 100)))
            }
            vm.setAccentColor(androidx.compose.ui.graphics.Color(Color.MAGENTA))
        }
        await { notification()?.color == Color.MAGENTA && notification()?.publicVersion?.color == Color.MAGENTA }
        assertEquals(token, MusicService.sessionToken)
    }

    private fun stripedFixture(horizontal: Boolean): Uri {
        val file = File.createTempFile("crop-fixture-", ".png", context.cacheDir)
        files.add(file)
        val image = Bitmap.createBitmap(if (horizontal) 192 else 64, if (horizontal) 64 else 192,
            Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
            val paint = android.graphics.Paint().apply { this.color = color }
            canvas.drawRect(if (horizontal) index * 64f else 0f, if (horizontal) 0f else index * 64f,
                if (horizontal) (index + 1) * 64f else 64f, if (horizontal) 64f else (index + 1) * 64f, paint)
        }
        try { file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        return Uri.fromFile(file)
    }

    @Test fun verticalCropUpdatesBothCardsAndSessionWithoutReplacingPlayback() {
        val token = MusicService.sessionToken
        main { vm.onCustomBackgroundPicked(stripedFixture(false)) }
        awaitColor(Color.GREEN)
        main { vm.artworkCrop.setPosition(ArtworkCrop(y = 0f)) }
        awaitColor(Color.RED)
        main { vm.artworkCrop.setPosition(ArtworkCrop(y = 1f)) }
        awaitColor(Color.BLUE)
        main { vm.artworkCrop.setPosition(ArtworkCrop()) }
        awaitColor(Color.GREEN)
        assertEquals(token, MusicService.sessionToken)
    }

    @Test fun horizontalCropUsesTheCurrentlySelectedLandscapeAndHonorsBackgroundToggle() {
        main { vm.onCustomBackgroundPicked(stripedFixture(true)) }
        awaitColor(Color.GREEN)
        main { vm.artworkCrop.setPosition(ArtworkCrop(x = 0f)) }
        awaitColor(Color.RED)
        main { vm.artworkCrop.setPosition(ArtworkCrop(x = 1f)) }
        awaitColor(Color.BLUE)
        main { vm.setCustomBgEnabled(false) }
        await { artwork() == null && notification()?.getLargeIcon() == null }
        main { vm.setCustomBgEnabled(true) }
        awaitColor(Color.BLUE)
    }


    @Test fun zoomChangesPublishedArtworkPixelsAndRemainsBounded() {
        val file = File.createTempFile("zoom-fixture-", ".png", context.cacheDir)
        files.add(file)
        val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val canvas = android.graphics.Canvas(image)
        canvas.drawRect(48f, 48f, 80f, 80f, android.graphics.Paint().apply { color = Color.GREEN })
        try { file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        main { vm.onCustomBackgroundPicked(Uri.fromFile(file)) }
        await { artwork()?.getPixel(8, 128) == Color.RED }
        main { vm.artworkCrop.setPosition(ArtworkCrop(zoom = 4f)) }
        awaitColor(Color.GREEN)
        await {
            val cards = listOf(notification(), notification()?.publicVersion)
            artwork()?.getPixel(8, 128) == Color.GREEN && cards.all { card ->
                val icon = (card?.getLargeIcon()?.loadDrawable(context) as? BitmapDrawable)?.bitmap
                icon?.getPixel(icon.width / 16, icon.height / 2) == Color.GREEN
            }
        }
        assertEquals(256, artwork()!!.width)
        assertTrue(artwork()!!.allocationByteCount <= 256 * 256 * 4)
    }

}
