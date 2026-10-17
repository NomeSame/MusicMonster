package com.nomesame.musicmonster.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.Player
import com.nomesame.musicmonster.MusicService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Attaches UI to the existing local service player. Closing video never stops audio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlaybackConnection(context: Context) {
    private val app = context.applicationContext
    private var bound = false
    private val _player = MutableStateFlow<Player?>(null)
    val player = _player.asStateFlow()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _player.value = (binder as? MusicService.VideoBinder)?.playbackPlayer()
        }
        override fun onServiceDisconnected(name: ComponentName?) { _player.value = null }
        override fun onBindingDied(name: ComponentName?) { disconnect() }
        override fun onNullBinding(name: ComponentName?) { disconnect() }
    }
    fun connect() {
        if (bound) return
        bound = app.bindService(Intent(app, MusicService::class.java)
            .setAction("com.nomesame.musicmonster.LOCAL_VIDEO"), connection, Context.BIND_AUTO_CREATE)
    }
    fun disconnect() {
        _player.value = null
        if (bound) app.unbindService(connection)
        bound = false
    }
}
