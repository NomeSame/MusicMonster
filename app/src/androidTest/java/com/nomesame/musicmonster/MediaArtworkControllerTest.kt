package com.nomesame.musicmonster

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import com.nomesame.musicmonster.model.MediaAppearance
import com.nomesame.musicmonster.playback.MediaArtworkController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaArtworkControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var prefs: SharedPreferences
    private lateinit var controller: MediaArtworkController
    private val release = CountDownLatch(1)
    private val loads = AtomicInteger()
    private val callbacks = AtomicInteger()

    @Before fun setup() {
        prefs = instrumentation.targetContext.getSharedPreferences("artwork_controller_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After fun cleanup() {
        release.countDown()
        main { if (::controller.isInitialized) controller.close() }
        prefs.edit().clear().commit()
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            main { matched = condition() }
            if (matched) return
            SystemClock.sleep(25)
        }
        fail("Artwork state was not reached before deadline")
    }

    private fun start(loader: (MediaAppearance) -> Bitmap? = { image(Color.RED) }) {
        main {
            controller = MediaArtworkController(MediaAppearanceRepository(prefs), {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                loads.incrementAndGet()
                loader(it)
            }, { callbacks.incrementAndGet() })
            controller.start()
            controller.start() // Idempotent observer registration.
        }
    }

    private fun image(color: Int) = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun accentRefreshDoesNotDecodeAgainAndClosedControllerStopsObserving() {
        start()
        await { controller.artwork != null }
        val readyCallbacks = callbacks.get()
        main { prefs.edit().putInt(MediaAppearanceRepository.KEY_ACCENT, Color.BLUE).commit() }
        await { callbacks.get() > readyCallbacks }
        assertEquals(1, loads.get())
        main { controller.close() }
        val closedCallbacks = callbacks.get()
        main { prefs.edit().putInt(MediaAppearanceRepository.KEY_ACCENT, Color.GREEN).commit() }
        SystemClock.sleep(200)
        assertEquals(closedCallbacks, callbacks.get())
    }

    @Test fun pendingOldImageCannotOverwriteNewImage() {
        val entered = CountDownLatch(1)
        start {
            if (it.backgroundUri == null) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                image(Color.RED)
            } else image(Color.BLUE)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        main { prefs.edit().putString(BackgroundRepository.KEY_URI, "file://new").commit() }
        SystemClock.sleep(200) // Ensure replacement is queued behind the blocked first decode.
        release.countDown()
        await { controller.artwork?.getPixel(0, 0) == Color.BLUE }
        assertEquals(2, loads.get())
    }

    @Test fun disablingDuringDecodeCannotResurrectTheOldImage() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        start {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            image(Color.RED).also { finished.countDown() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        main { BackgroundRepository(prefs).setEnabled(false) }
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(200)
        main { assertNull(controller.artwork) }
        assertEquals(1, loads.get())
    }

    @Test fun rapidChangesDecodeOnlyTheFinalBackground() {
        main {
            prefs.edit().putBoolean(BackgroundRepository.KEY_ENABLED, false).commit()
        }
        start { if (it.backgroundUri == "file://last") image(Color.BLUE) else image(Color.RED) }
        main {
            prefs.edit().putBoolean(BackgroundRepository.KEY_ENABLED, true).commit()
            listOf("first", "second", "last").forEach {
                prefs.edit().putString(BackgroundRepository.KEY_URI, "file://$it").commit()
            }
        }
        await { controller.artwork != null }
        main { assertEquals(Color.BLUE, controller.artwork!!.getPixel(0, 0)) }
        assertEquals(1, loads.get())
    }

    @Test fun closeRejectsAnAlreadyRunningDecodeEvenWhenItIgnoresInterruption() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        start {
            entered.countDown()
            try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                // Some document providers cannot cancel native I/O immediately.
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            image(Color.RED).also { finished.countDown() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        main { controller.close() }
        val closedCallbacks = callbacks.get()
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(200)
        main { assertNull(controller.artwork) }
        assertEquals(closedCallbacks, callbacks.get())
    }
}
