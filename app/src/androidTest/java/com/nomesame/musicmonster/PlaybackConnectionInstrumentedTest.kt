package com.nomesame.musicmonster

import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.playback.PlaybackConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The actual Binder/session contract, independent of Robolectric shadows. */
@RunWith(AndroidJUnit4::class)
class PlaybackConnectionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var session: MediaSessionCompat
    private lateinit var connection: PlaybackConnection
    private val audioIds = mutableListOf<Int>()
    @Before fun setup() {
        instrumentation.runOnMainSync {
            session = MediaSessionCompat(instrumentation.targetContext, "boundary-real-session")
            session.isActive = true
            session.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "probe")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Probe")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 1000).build())
            session.setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 250, 1f).build())
            session.setExtras(Bundle().apply { putInt("audio_session_id", 42) })
            connection = PlaybackConnection(instrumentation.targetContext)
            connection.onAudioSession = { audioIds.add(it) }
            connection.connect({ session.sessionToken })
        }
        await("initial connection") { connection.isReady.value }
    }
    @After fun teardown() {
        if (::connection.isInitialized) instrumentation.runOnMainSync { connection.disconnect() }
        if (::session.isInitialized) instrumentation.runOnMainSync { session.release() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $message")
    }
    @Test fun reopeningSeedsAnAlreadyPlayingSession() {
        assertTrue(connection.isPlaying.value)
        assertEquals("probe", connection.nowPlayingId.value)
        assertEquals("Probe", connection.nowPlayingTitle.value)
        assertEquals(1000L, connection.duration.value)
        assertEquals(42, audioIds.first())
    }
    @Test fun realSessionReleaseClearsPlaybackAndAudioEffects() {
        instrumentation.runOnMainSync { session.release() }
        await("released session") { !connection.isReady.value }
        assertFalse(connection.isPlaying.value)
        assertNull(connection.nowPlayingId.value)
        assertEquals(0L, connection.duration.value)
        assertEquals(0, audioIds.last())
    }
    @Test fun disconnectPreventsFurtherSessionUpdates() {
        instrumentation.runOnMainSync {
            connection.disconnect()
            session.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "late").build())
        }
        instrumentation.waitForIdleSync()
        assertFalse(connection.isReady.value)
        assertNull(connection.nowPlayingId.value)
        assertEquals(0, audioIds.last())
    }
}
