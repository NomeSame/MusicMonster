package com.nomesame.musicmonster

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.sin

/**
 * Drives the real MediaSession the way the lock screen, a headset button and
 * the notification do, against a real ExoPlayer and a real audio file.
 *
 * This is the layer the unit tests structurally cannot reach: whether the
 * service actually reaches STATE_PLAYING, whether transport commands survive
 * being fired faster than the player can react, and whether a command aimed at
 * an empty queue takes the service down.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaybackSessionInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var inserted: Uri? = null
    private lateinit var controller: MediaControllerCompat

    /** A one-second 8kHz mono sine as a WAV — small, and ExoPlayer plays it. */
    private fun wavBytes(): ByteArray {
        val sampleRate = 8000
        val samples = sampleRate
        val body = ByteArrayOutputStream()
        for (i in 0 until samples) {
            val v = (12000 * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt()
            body.write(v and 0xFF)
            body.write((v shr 8) and 0xFF)
        }
        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        fun int32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun int16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); int32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int32(16); int16(1); int16(1)
        int32(sampleRate); int32(sampleRate * 2); int16(2); int16(16)
        out.write("data".toByteArray()); int32(data.size); out.write(data)
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        TestPermissions.grantAll()
        // The library must come from MediaStore, not a stale SAF folder from
        // another test or a previous manual run.
        context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            .edit().remove("library_tree_uri").commit()
        inserted = insertTestTrack()
        startServiceAndConnect()
    }

    @After
    fun tearDown() {
        inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        context.stopService(Intent(context, MusicService::class.java))
    }

    private fun insertTestTrack(): Uri? = runCatching {
        val name = "monster_probe_${System.currentTimeMillis()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, "Monster Probe")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MonsterProbe")
            }
        }
        val uri = context.contentResolver
            .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        context.contentResolver.openOutputStream(uri)?.use { it.write(wavBytes()) }
        uri
    }.getOrNull()

    private fun startServiceAndConnect() {
        val intent = Intent(context, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        val token = await("session token") { MusicService.sessionToken } ?: return
        controller = MediaControllerCompat(context, token)
        // Pick up the track this test just inserted.
        context.startService(
            Intent(context, MusicService::class.java)
                .setAction(MusicService.ACTION_RELOAD_LIBRARY)
        )
        awaitLibrary()
    }

    /**
     * Waits for the library scan to publish a real track. The scan runs off the
     * main thread, so a fixed sleep here would make every assertion below a
     * function of how fast the machine is.
     */
    private fun awaitLibrary(): Boolean = await("library", timeoutMs = 15_000L) {
        controller.metadata
            ?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE)
            ?.takeIf { it != NO_SONG }
    } != null

    private fun <T> await(what: String, timeoutMs: Long = 8_000L, probe: () -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            probe()?.let { return it }
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun awaitState(vararg wanted: Int, timeoutMs: Long = 8_000L): Int? =
        await("state ${wanted.toList()}", timeoutMs) {
            controller.playbackState?.state?.takeIf { wanted.contains(it) }
        }

    @Test
    fun sessionBecomesAvailableAndAcceptsCommands() {
        assertNotNull("MusicService never published a session token", MusicService.sessionToken)
        assertNotNull("controller has no playback state", controller.playbackState)
    }

    @Test
    fun testTrackIsActuallyProvisioned() {
        // Guards the guard: on API 29+ an app may always insert its own audio
        // into MediaStore, so a null here means the tests below quietly took
        // their degraded branch and stopped being able to fail.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertNotNull(
                "MediaStore insert failed — the playback tests would silently " +
                    "run against an empty library and could no longer go red",
                inserted
            )
        }
    }

    @Test
    fun playReachesPlayingWithARealTrack() {
        if (inserted == null) {
            // No MediaStore write access on this configuration: still assert
            // the contract that matters — play() on an empty library must
            // leave a live, non-crashed session behind, not kill the service.
            controller.transportControls.play()
            SystemClock.sleep(1_500L)
            assertNotNull("service died on play() with an empty library", MusicService.sessionToken)
            return
        }
        controller.transportControls.play()
        val state = awaitState(PlaybackStateCompat.STATE_PLAYING)
        assertEquals(
            "player never reached STATE_PLAYING for a real audio file",
            PlaybackStateCompat.STATE_PLAYING,
            state
        )
    }

    @Test
    fun pauseAfterPlayIsReported() {
        controller.transportControls.play()
        awaitState(PlaybackStateCompat.STATE_PLAYING, timeoutMs = 5_000L)
        controller.transportControls.pause()
        assertEquals(
            "pause was not reflected in the session state",
            PlaybackStateCompat.STATE_PAUSED,
            awaitState(PlaybackStateCompat.STATE_PAUSED)
        )
    }

    @Test
    fun rapidTransportSpamNeverKillsTheService() {
        // Mashing the notification buttons: skip/seek/shuffle faster than the
        // player can settle. Each of these has an index or a position that can
        // go out of range if the queue changes underneath it.
        repeat(15) {
            controller.transportControls.skipToNext()
            controller.transportControls.skipToPrevious()
            controller.transportControls.seekTo(-1L)
            controller.transportControls.seekTo(Long.MAX_VALUE)
            controller.transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
            controller.transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE)
        }
        SystemClock.sleep(2_000L)
        assertNotNull("service died under transport spam", MusicService.sessionToken)
        assertNotNull("session stopped reporting state", controller.playbackState)
    }

    @Test
    fun playFromUnknownMediaIdIsIgnoredNotFatal() {
        // The id of a song that has left the library — what a stale UI list or
        // a playlist entry hands over.
        controller.transportControls.playFromMediaId("no-such-song-id", null)
        SystemClock.sleep(1_500L)
        assertNotNull("service died on an unknown media id", MusicService.sessionToken)
    }

    @Test
    fun seekBeyondTrackEndIsClamped() {
        controller.transportControls.play()
        awaitState(PlaybackStateCompat.STATE_PLAYING, timeoutMs = 5_000L)
        controller.transportControls.seekTo(Long.MAX_VALUE / 2)
        SystemClock.sleep(1_000L)
        val position = controller.playbackState?.position ?: 0L
        assertTrue("position ran away to $position", position >= 0L)
        assertNotNull("service died on an out-of-range seek", MusicService.sessionToken)
    }

    @Test
    fun playRequestedBeforeTheLibraryIsReadyStillPlays() {
        if (inserted == null) return
        // The cold-start race: the session is published as soon as the service
        // goes foreground, but the library scan runs off the main thread. A
        // lock-screen or headset play arriving in that window must be honoured
        // once the scan lands, not dropped — dropping it is invisible on a fast
        // device with a small library and reproducible on a slow one with a big
        // library.
        context.stopService(Intent(context, MusicService::class.java))
        SystemClock.sleep(500L)
        val intent = Intent(context, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        val token = await("session token") { MusicService.sessionToken }
        assertNotNull("service never published a session", token)
        val fresh = MediaControllerCompat(context, token!!)
        // Fire immediately — before the scan can possibly have finished.
        fresh.transportControls.play()
        val state = await("playing", timeoutMs = 20_000L) {
            fresh.playbackState?.state?.takeIf { it == PlaybackStateCompat.STATE_PLAYING }
        }
        assertEquals(
            "play() issued during the library scan was dropped",
            PlaybackStateCompat.STATE_PLAYING,
            state
        )
    }

    @Test
    fun shuffleSurvivesASingleTrackQueue() {
        // DefaultShuffleOrder is the classic crash with 0 or 1 items; the
        // probe library here is exactly that size.
        controller.transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
        SystemClock.sleep(800L)
        controller.transportControls.skipToNext()
        SystemClock.sleep(800L)
        assertNotNull("service died shuffling a one-track queue", MusicService.sessionToken)
    }

    private companion object {
        /** What MusicService reports when no track is selected yet. */
        const val NO_SONG = "No song selected"
    }
}
