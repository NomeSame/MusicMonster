package com.nomesame.musicmonster

import android.os.Handler
import android.os.Looper
import com.nomesame.musicmonster.data.PlaybackStateStore
import com.nomesame.musicmonster.model.PlaybackResumeState
import com.nomesame.musicmonster.playback.PlaybackCheckpointController
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PlaybackCheckpointControllerTest {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Thread.currentThread()
    private var sample: PlaybackResumeState? = PlaybackResumeState("song", 0, false)
    private val writes = Collections.synchronizedList(mutableListOf<PlaybackResumeState>())
    private val fail = AtomicBoolean(false)
    private var blocker: (() -> Unit)? = null
    private val store = object : PlaybackStateStore {
        @Volatile var value = PlaybackResumeState("old", 500, false)
        override fun load() = value
        override fun save(state: PlaybackResumeState): Boolean {
            assertNotSame("Disk write on the main thread", main, Thread.currentThread())
            blocker?.invoke()
            writes.add(state)
            if (fail.get()) return false
            value = state
            return true
        }
    }
    private val controller = PlaybackCheckpointController(store, { sample }, Handler(Looper.getMainLooper()), worker)
    private fun settle() { worker.submit {}.get(5, TimeUnit.SECONDS) }
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); settle() }

    @Test fun activePlaybackPersistsEveryFiveSecondsWithoutClosingTheApp() {
        try {
            controller.setPlaying(true); settle()
            sample = PlaybackResumeState("song", 4999, false)
            advance(4999)
            assertEquals(0L, controller.lastPersisted!!.positionMs)
            sample = PlaybackResumeState("song", 5000, false)
            advance(1)
            assertEquals(5000L, controller.lastPersisted!!.positionMs)
            sample = PlaybackResumeState("song", 10000, false)
            advance(5000)
            assertEquals(10000L, store.load().positionMs)
            assertEquals(3, writes.size)
        } finally { controller.close() }
    }
    @Test fun pauseSavesImmediatelyAndCancelsPeriodicWrites() {
        try {
            controller.setPlaying(true); settle()
            sample = PlaybackResumeState("song", 4321, true)
            controller.setPlaying(false); settle()
            assertEquals(sample, store.load())
            sample = PlaybackResumeState("song", 9999, true)
            advance(20000)
            assertEquals(4321L, store.load().positionMs)
        } finally { controller.close() }
    }
    @Test fun absentItemPreservesOldStateAndUnchangedSnapshotsDoNotWriteAgain() {
        try {
            sample = null
            controller.saveNow(); settle()
            assertTrue(writes.isEmpty())
            assertEquals("old", controller.load().songId)
            sample = PlaybackResumeState("song", 7000, false)
            controller.saveNow(); settle()
            repeat(20) { controller.saveNow() }; settle()
            assertEquals(1, writes.size)
        } finally { controller.close() }
    }
    @Test fun failedWriteIsRetriedAtNextCheckpoint() {
        try {
            fail.set(true)
            controller.setPlaying(true); settle()
            assertNull(controller.lastPersisted)
            assertEquals("old", store.load().songId)
            fail.set(false)
            advance(5000)
            assertEquals(sample, controller.lastPersisted)
            assertEquals(2, writes.size)
        } finally { controller.close() }
    }
    @Test fun slowWriteCoalescesLatestStateAndCannotOverwriteNewerCheckpoint() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        blocker = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        try {
            controller.saveNow()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) {
                sample = PlaybackResumeState("latest", it.toLong(), true)
                controller.saveNow()
            }
            assertEquals(sample, controller.load())
            release.countDown(); settle()
            assertEquals(sample, store.load())
            assertEquals(2, writes.size)
        } finally { release.countDown(); controller.close() }
    }
    @Test fun closeFinishesQueuedSnapshotAndStopsCallbacksWithoutSamplingReleasedPlayer() {
        controller.setPlaying(true); settle()
        sample = PlaybackResumeState("song", 9876, false)
        controller.saveNow()
        controller.close(); controller.close()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(sample, store.load())
        sample = PlaybackResumeState("released", 0, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        controller.saveNow(); controller.setPlaying(true)
        assertEquals(9876L, store.load().positionMs)
    }
}
