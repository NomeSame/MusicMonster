package com.nomesame.musicmonster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real disk + abrupt subprocess death; no force-stop/reboot of the user's phone or main app. */
@RunWith(AndroidJUnit4::class)
class PlaybackCheckpointProcessTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wav: File
    private val preferenceName = "checkpoint_process_fixture_" + UUID.randomUUID().toString()
    private var client: Client? = null

    @Before fun setup() {
        wav = File.createTempFile("checkpoint-fixture-", ".wav", ctx.cacheDir)
        val size = 8000 * 2 * 60
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
        wave.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
        wave.putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        wav.writeBytes(wave.array())
        client = Client()
        await("real WAV ready") { client!!.request(4).getInt("state") == Player.STATE_READY }
    }
    @After fun cleanup() {
        client?.let { it.killAndDisconnect() }
        assertTrue(ctx.deleteSharedPreferences(preferenceName))
        if (::wav.isInitialized) assertTrue(wav.delete())
    }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $message")
    }
    private fun reopenAfterKill(previousPid: Int): Bundle {
        client!!.killAndDisconnect()
        client = Client()
        var restored = Bundle()
        await("new process restores WAV") {
            restored = client!!.request(4)
            restored.getInt("state") == Player.STATE_READY
        }
        assertNotEquals(previousPid, restored.getInt("pid"))
        assertFalse(restored.getBoolean("playing"))
        return restored
    }
    @Test fun periodicCheckpointSurvivesKillWithoutPauseOrLifecycleSave() {
        client!!.request(3, 12000)
        client!!.request(1)
        var snapshot = Bundle()
        await("periodic checkpoint beyond explicit seek") {
            snapshot = client!!.request(4)
            snapshot.getBoolean("playing") && snapshot.getLong("saved_position") >= 16000
        }
        assertEquals("probe-second", snapshot.getString("saved_song"))
        assertNotEquals(Process.myPid(), snapshot.getInt("pid"))
        val restored = reopenAfterKill(snapshot.getInt("pid"))
        assertEquals("probe-second", restored.getString("song"))
        assertTrue(restored.getLong("position") >= snapshot.getLong("saved_position"))
        assertTrue(snapshot.getLong("position") - restored.getLong("position") <= 6000)
        assertTrue(restored.getLong("position") <= snapshot.getLong("position") + 1000)
    }
    @Test fun pausedPositionSurvivesAbruptDeathExactly() {
        client!!.request(3, 23000)
        client!!.request(1)
        await("playing before pause") { client!!.request(4).getBoolean("playing") }
        SystemClock.sleep(250)
        val paused = client!!.request(2)
        assertFalse(paused.getBoolean("playing"))
        val position = paused.getLong("position")
        await("pause disk commit") { client!!.request(4).getLong("saved_position") == position }
        val restored = reopenAfterKill(paused.getInt("pid"))
        assertEquals("probe-second", restored.getString("song"))
        assertEquals(position, restored.getLong("position"))
    }

    private inner class Client : ServiceConnection {
        @Volatile private var remote: Messenger? = null
        @Volatile private var generation = 0
        private val responses = LinkedBlockingQueue<Message>()
        private val reply = Messenger(Handler(Looper.getMainLooper()) {
            responses.offer(Message.obtain(it)); true
        })
        private var sequence = 0
        private var bound = false
        init {
            val intent = Intent().setClassName(ctx.packageName,
                "com.nomesame.musicmonster.debug.PlaybackResumeProbeService")
                .putExtra("prefs", preferenceName).putExtra("fixture_uri", Uri.fromFile(wav).toString())
            bound = ctx.bindService(intent, this, Context.BIND_AUTO_CREATE)
            assertTrue("Debug probe did not bind", bound)
            await("probe binding") { remote != null }
        }
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder); generation++
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
        fun request(action: Int, position: Long = 0): Bundle {
            val id = ++sequence
            remote!!.send(Message.obtain(null, action).apply {
                arg1 = id; replyTo = reply; data = Bundle().apply { putLong("position", position) }
            })
            val response = responses.poll(5, TimeUnit.SECONDS)
            assertNotNull("No probe response for $action", response)
            assertEquals(id, response!!.arg1)
            return response.data
        }
        fun killAndDisconnect() {
            if (!bound) return
            val oldGeneration = generation
            val target = remote
            if (target != null) {
                target.send(Message.obtain(null, 5))
                await("isolated process death") { remote == null || generation > oldGeneration }
            }
            ctx.unbindService(this)
            bound = false
            remote = null
        }
    }
}
