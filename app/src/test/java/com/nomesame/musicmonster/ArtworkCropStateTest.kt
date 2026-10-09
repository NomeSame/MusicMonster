package com.nomesame.musicmonster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import com.nomesame.musicmonster.model.ArtworkCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtworkCropStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("crop_preview_fixture", Context.MODE_PRIVATE)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Preview state did not arrive")
    }
    private fun image(color: Int) = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun cropAndDimChangesReuseDecodedSourceButNewBackgroundReloads() {
        prefs.edit().clear().commit()
        val loads = AtomicInteger()
        val owner = Job()
        val state = ArtworkCropState(MediaAppearanceRepository(prefs), CoroutineScope(owner + Dispatchers.Main)) {
            loads.incrementAndGet(); image(Color.RED)
        }
        try {
            state.openPreview()
            await { state.preview.value != null }
            state.setPosition(ArtworkCrop(0f, 1f))
            prefs.edit().putFloat(BackgroundRepository.KEY_SCRIM, 0.2f).commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, loads.get())
            prefs.edit().putString(BackgroundRepository.KEY_URI, "file://changed").commit()
            await { loads.get() == 2 && state.preview.value != null }
            state.closePreview()
            assertNull(state.preview.value)
            prefs.edit().putString(BackgroundRepository.KEY_URI, "file://closed").commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, loads.get())
        } finally { state.close(); owner.cancel() }
    }

    @Test fun anOldSlowDecodeCannotReplaceTheNewBackground() {
        prefs.edit().clear().commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = Job()
        val state = ArtworkCropState(MediaAppearanceRepository(prefs), CoroutineScope(owner + Dispatchers.Main)) {
            if (it.backgroundUri == null) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                image(Color.RED)
            } else image(Color.BLUE)
        }
        try {
            state.openPreview()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            prefs.edit().putString(BackgroundRepository.KEY_URI, "file://new").commit()
            await { state.preview.value?.getPixel(0, 0) == Color.BLUE }
            release.countDown()
            await { !state.loading.value }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Color.BLUE, state.preview.value!!.getPixel(0, 0))
        } finally { release.countDown(); state.close(); owner.cancel() }
    }

    @Test fun disabledBackgroundAndUnreadablePreviewCompleteWithoutStuckSpinner() {
        prefs.edit().clear().putBoolean(BackgroundRepository.KEY_ENABLED, false).commit()
        val owner = Job()
        val state = ArtworkCropState(MediaAppearanceRepository(prefs), CoroutineScope(owner + Dispatchers.Main)) { null }
        try {
            state.openPreview()
            assertNull(state.preview.value)
            assertFalse(state.loading.value)
            prefs.edit().putBoolean(BackgroundRepository.KEY_ENABLED, true).commit()
            await { !state.loading.value }
            assertNull(state.preview.value)
        } finally { state.close(); owner.cancel() }
    }
}
