package com.nomesame.musicmonster

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.nomesame.musicmonster.playback.VideoPlaybackConnection
import com.nomesame.musicmonster.playback.VideoPipController
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.components.VideoPlayerContent
import com.nomesame.musicmonster.data.LanguageRepository
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

/** Video surface for the existing service player; Android owns its floating PiP window. */
class VideoActivity : ComponentActivity() {
    private lateinit var connection: VideoPlaybackConnection
    private lateinit var pip: VideoPipController
    private var small by mutableStateOf(false)
    private var pipSession = false
    private var audioOnly = false
    private var observed: Player? = null
    private val mediaListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (mediaItem == null) continueAudio()
        }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // SAF document IDs need not contain a filename or extension. Use decoded tracks.
            if (!tracks.isEmpty && !tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) continueAudio()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = VideoPlaybackConnection(this)
        pip = VideoPipController(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = continueAudio()
        })
        lifecycleScope.launch {
            connection.player.collect { player ->
                observed?.removeListener(mediaListener)
                observed = player
                player?.addListener(mediaListener)
                pip.attach(player)
            }
        }
        setContent {
            val player by connection.player.collectAsState()
            AppLanguageContent(LanguageRepository(this).load()) {
                MyApplicationTheme {
                    VideoPlayerContent(player, small, ::continueAudio,
                        if (pip.supported) ({ shrink() }) else null, pip::sourceBounds)
                }
            }
        }
    }

    private fun continueAudio() {
        audioOnly = true
        pip.disable()
        finish()
    }

    private fun shrink() {
        if (!pip.enter()) Toast.makeText(this, R.string.video_pip_unavailable, Toast.LENGTH_LONG).show()
    }

    override fun onStart() { super.onStart(); connection.connect() }
    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) pipSession = false
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); pip.onUserLeaveHint() }
    override fun onPictureInPictureUiStateChanged(pipState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (android.os.Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) small = true
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        small = isInPictureInPictureMode
        if (small) pipSession = true
    }
    override fun onStop() {
        // Entering PiP pauses the Activity but does not stop it. Closing its system window does.
        if (pipSession && !audioOnly && !isChangingConfigurations) {
            observed?.pause()
            finish()
        }
        connection.disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        observed?.removeListener(mediaListener)
        pip.close()
        connection.disconnect()
        super.onDestroy()
    }
}
