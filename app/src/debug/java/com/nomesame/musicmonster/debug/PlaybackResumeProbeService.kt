package com.nomesame.musicmonster.debug

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nomesame.musicmonster.data.PlaybackStateRepository
import com.nomesame.musicmonster.model.PlaybackResumeState
import com.nomesame.musicmonster.playback.PlaybackCheckpointController

/** Debug-only bound test fixture in a separate process, never the user's playback service. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackResumeProbeService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var repository: PlaybackStateRepository
    private lateinit var checkpoints: PlaybackCheckpointController
    private val handler = Handler(Looper.getMainLooper(), ::handleRequest)

    private fun handleRequest(message: Message): Boolean {
        when (message.what) {
            1 -> player.play()
            2 -> player.pause()
            3 -> player.seekTo(1, message.data.getLong("position"))
            5 -> {
                // Intentionally bypass onDestroy, exactly the case periodic persistence must survive.
                handler.postDelayed({ Process.killProcess(Process.myPid()) }, 100)
            }
        }
        if (message.what != 5) {
            val saved = checkpoints.lastPersisted ?: repository.load()
            val response = Message.obtain(null, message.what).apply {
                arg1 = message.arg1
                data = Bundle().apply {
                    putInt("pid", Process.myPid())
                    putInt("state", player.playbackState)
                    putBoolean("playing", player.isPlaying)
                    putString("song", player.currentMediaItem?.mediaId)
                    putLong("position", player.currentPosition)
                    putString("saved_song", saved.songId)
                    putLong("saved_position", saved.positionMs)
                }
            }
            message.replyTo?.send(response)
        }
        return true
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        player.volume = 0f
    }
    override fun onBind(intent: Intent): IBinder {
        repository = PlaybackStateRepository(getSharedPreferences(intent.getStringExtra("prefs")!!, MODE_PRIVATE))
        val saved = repository.load()
        val uri = intent.getStringExtra("fixture_uri")!!
        val items = listOf("probe-first", "probe-second").map {
            MediaItem.Builder().setMediaId(it).setUri(uri).build()
        }
        val index = items.indexOfFirst { it.mediaId == saved.songId }
        player.setMediaItems(items, index.coerceAtLeast(0), if (index >= 0) saved.positionMs else 0)
        player.shuffleModeEnabled = saved.shuffleEnabled
        checkpoints = PlaybackCheckpointController(repository, {
            player.currentMediaItem?.let { PlaybackResumeState(it.mediaId, player.currentPosition, player.shuffleModeEnabled) }
        })
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { checkpoints.setPlaying(isPlaying) }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) { checkpoints.saveNow() }
        })
        player.prepare()
        return messenger.binder
    }
    override fun onDestroy() {
        if (::checkpoints.isInitialized) { checkpoints.saveNow(); checkpoints.close() }
        player.release()
        super.onDestroy()
    }
}
