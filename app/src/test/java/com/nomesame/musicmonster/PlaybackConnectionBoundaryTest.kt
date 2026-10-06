package com.nomesame.musicmonster

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.playback.PlaybackConnection
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowMediaController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], shadows = [SeededMediaController::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackConnectionBoundaryTest {
    private lateinit var session: MediaSessionCompat
    private lateinit var connection: PlaybackConnection
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        session = MediaSessionCompat(context, "connection-boundary")
        session.isActive = true
        SeededMediaController.initialMetadata = MediaMetadata.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Track")
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "track")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 1000).build()
        SeededMediaController.initialState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 300, 1f).build()
        SeededMediaController.initialExtras = Bundle().apply { putInt("audio_session_id", 42) }
        connection = PlaybackConnection(context)
    }
    @After fun teardown() { connection.disconnect(); session.release() }
    private fun connect() {
        session.setPlaybackState(PlaybackStateCompat.fromPlaybackState(SeededMediaController.initialState))
        connection.connect({ session.sessionToken })
        shadowOf(Looper.getMainLooper()).idle()
    }
    // Robolectric 4.12 does not link the session/controller Binder. Seed its
    // documented controller shadow and dispatch real controller callbacks;
    // the actual Binder contract is covered by the instrumented companion.
    private fun shadowController(): ShadowMediaController {
        val field = PlaybackConnection::class.java.getDeclaredField("mediaController")
        field.isAccessible = true
        val compat = field.get(connection) as android.support.v4.media.session.MediaControllerCompat
        return shadowOf(compat.mediaController as MediaController)
    }
    @Test fun repeatedConnectDoesNotDuplicateBindings() {
        val ids = mutableListOf<Int>()
        connection.onAudioSession = { ids.add(it) }
        connect()
        connect()
        assertEquals(listOf(42), ids)
        assertEquals(1, shadowController().callbacks.size)
    }
    @Test fun disconnectUnregistersCallbacksAndClearsPlayback() {
        connect()
        val controller = shadowController()
        connection.disconnect()
        assertTrue(controller.callbacks.isEmpty())
        assertFalse(connection.isReady.value)
        assertFalse(connection.isPlaying.value)
        assertNull(connection.nowPlayingId.value)
    }
    @Test fun disconnectStopsTokenPolling() {
        var probes = 0
        connection.connect({ probes++; null })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, probes)
        connection.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1, probes)
    }
    @Test fun lateSessionStillConnectsAfterUnavailableTimeout() {
        var available = false
        var notices = 0
        session.setPlaybackState(PlaybackStateCompat.fromPlaybackState(SeededMediaController.initialState))
        connection.connect({ if (available) session.sessionToken else null }, { notices++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(1, notices)
        assertFalse(connection.isReady.value)
        available = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(connection.isReady.value)
        assertTrue(connection.isPlaying.value)
        assertEquals(1, notices)
    }
    @Test fun bindingToAlreadyPlayingSessionSeedsPlayingState() {
        connect()
        assertTrue(connection.isReady.value)
        assertTrue(connection.isPlaying.value)
        assertEquals("track", connection.nowPlayingId.value)
        assertEquals(300L, connection.position.value)
    }
    @Test fun sessionDestructionClearsStalePlaybackState() {
        connect()
        shadowController().executeOnSessionDestroyed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(connection.isReady.value)
        assertFalse(connection.isPlaying.value)
        assertNull(connection.nowPlayingId.value)
        assertNull(connection.nowPlayingTitle.value)
        assertEquals(0L, connection.duration.value)
        assertEquals(0L, connection.position.value)
    }
    @Test fun sessionDestructionReleasesAudioEffects() {
        val ids = mutableListOf<Int>()
        connection.onAudioSession = { ids.add(it) }
        connect()
        shadowController().executeOnSessionDestroyed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(42, 0), ids)
    }
    @Test fun unknownInitialPlaybackPositionIsClamped() {
        SeededMediaController.initialState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, -1, 0f).build()
        connect()
        assertEquals(0L, connection.position.value)
        connection.refreshPosition()
        assertEquals(0L, connection.position.value)
    }
    @Test fun negativeMetadataDurationIsClamped() {
        SeededMediaController.initialMetadata = MediaMetadata.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1).build()
        connect()
        assertEquals(0L, connection.duration.value)
    }
    @Test fun validStateChangesContinueToPropagate() {
        connect()
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, 500, 0f).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(connection.isPlaying.value)
        assertEquals(500L, connection.position.value)
    }
}

@Implements(MediaController::class)
class SeededMediaController : ShadowMediaController() {
    @Implementation override fun getMetadata(): MediaMetadata? = initialMetadata
    @Implementation override fun getPlaybackState(): PlaybackState? = initialState
    @Implementation override fun getExtras(): Bundle? = initialExtras
    companion object {
        var initialMetadata: MediaMetadata? = null
        var initialState: PlaybackState? = null
        var initialExtras: Bundle? = null
    }
}
