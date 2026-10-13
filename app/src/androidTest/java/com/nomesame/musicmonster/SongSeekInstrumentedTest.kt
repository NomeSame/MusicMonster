package com.nomesame.musicmonster

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.playback.PlaybackConnection
import com.nomesame.musicmonster.playback.seekWithinSong
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real WAV/player/session: no user's service, queue or preferences are touched. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SongSeekInstrumentedTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private lateinit var file: File
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
    private lateinit var connection: PlaybackConnection
    private var seconds = 10
    private var commands = 0
    private fun <T> main(action: () -> T): T {
        var value: Any? = null
        ins.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $message")
    }
    @Before fun setup() {
        file = File.createTempFile("song-seek-", ".wav", ins.targetContext.cacheDir)
        val dataSize = 8000 * 2 * 60
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
        wav.putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
        file.writeBytes(wav.array())
        main {
            player = ExoPlayer.Builder(ins.targetContext).build()
            val item = MediaItem.Builder().setMediaId("seek-fixture").setUri(Uri.fromFile(file)).build()
            player.setMediaItems(listOf(item, item.buildUpon().setMediaId("second-fixture").build()))
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            session = MediaSessionCompat(ins.targetContext, "isolated-seek-fixture")
            session.setCallback(object : MediaSessionCompat.Callback() {
                override fun onRewind() { seekWithinSong(player, seconds, false); commands++ }
                override fun onFastForward() { seekWithinSong(player, seconds, true); commands++ }
            }, Handler(Looper.getMainLooper()))
            // Inactive session avoids replacing the user's system media card.
            session.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "seek-fixture")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 60000).build())
            session.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f).build())
            connection = PlaybackConnection(ins.targetContext)
            connection.connect({ session.sessionToken })
        }
        await("ready real WAV and connection") { main { player.playbackState == Player.STATE_READY && connection.isReady.value } }
    }
    @After fun cleanup() {
        main {
            if (::connection.isInitialized) connection.disconnect()
            if (::session.isInitialized) session.release()
            if (::player.isInitialized) player.release()
        }
        if (::file.isInitialized) assertTrue(file.delete())
    }
    @Test fun binderCommandsAccumulateFromActualPositionWithStaleSessionMetadata() {
        main { player.seekTo(20_000); repeat(3) { connection.fastForward() } }
        await("three forward commands") { main { commands == 3 } }
        main {
            assertEquals(50_000L, player.currentPosition)
            assertEquals(0, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
            seconds = 15
            connection.rewind()
        }
        await("custom rewind") { main { commands == 4 } }
        main { assertEquals(35_000L, player.currentPosition); assertEquals(0, player.currentMediaItemIndex) }
    }
    @Test fun beginningAndEndClampWithoutChangingSongEvenWithRepeatAll() {
        main { player.seekTo(2000); connection.rewind() }
        await("rewind boundary") { main { commands == 1 } }
        main { assertEquals(0L, player.currentPosition); player.seekTo(55_000); connection.fastForward() }
        await("forward boundary") { main { commands == 2 } }
        main {
            assertEquals(player.duration - 1L, player.currentPosition)
            assertEquals("seek-fixture", player.currentMediaItem!!.mediaId)
            assertEquals(0, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
        }
    }
    @Test fun playingJumpKeepsPlaybackRunningAndUsesChangedInterval() {
        main { player.volume = 0f; player.seekTo(10_000); player.play() }
        await("silent playback") { main { player.isPlaying } }
        val before = main { player.currentPosition }
        main { seconds = 5; connection.fastForward() }
        await("playing forward") { main { commands == 1 } }
        main {
            assertTrue(player.playWhenReady)
            assertTrue(player.currentPosition >= before + 5000)
            assertTrue(player.currentPosition < before + 7000)
            assertEquals(0, player.currentMediaItemIndex)
        }
        await("continues playing after jump") { main { player.isPlaying } }
    }

    @Test fun emptyPlayerCannotSeekOrSelectAnotherItem() {
        main {
            player.clearMediaItems()
            assertFalse(seekWithinSong(player, 10, true))
            assertFalse(seekWithinSong(player, 10, false))
            assertEquals(0, player.mediaItemCount)
        }
    }
}
