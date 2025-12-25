//package com.example.myapplication.ui
//
//import android.support.v4.media.session.MediaSessionCompat
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.layout.Arrangement
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.Row
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.padding
//import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.filled.Pause
//import androidx.compose.material.icons.filled.PlayArrow
//import androidx.compose.material.icons.filled.SkipNext
//import androidx.compose.material.icons.filled.SkipPrevious
//import androidx.compose.material3.Button
//import androidx.compose.material3.Icon
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Slider
//import androidx.compose.material3.Text
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.DisposableEffect
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.unit.dp
//import com.google.android.exoplayer2.C
//import com.google.android.exoplayer2.ExoPlayer
//import com.google.android.exoplayer2.MediaItem
//import com.google.android.exoplayer2.Player
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.isActive
//
//@Composable
//fun MusicPlayer(
//    player: ExoPlayer,
//    session: MediaSessionCompat,
//    modifier: Modifier = Modifier
//) {
//    var isPlaying by remember { mutableStateOf(false) }
//    var progress by remember { mutableStateOf(0f) }
//
//    fun updateProgress() {
//        val duration = player.duration
//        progress = if (duration > 0 && duration != C.TIME_UNSET) {
//            (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
//        } else {
//            0f
//        }
//    }
//
//    // ✅ Single listener lifecycle (no duplicate DisposableEffect)
//    DisposableEffect(player) {
//        val listener = object : Player.Listener {
//            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
//                isPlaying = isPlayingNow
//                updateProgress()
//            }
//
//            override fun onPlaybackStateChanged(state: Int) {
//                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
//                    updateProgress()
//                }
//            }
//
//            override fun onPositionDiscontinuity(
//                oldPosition: Player.PositionInfo,
//                newPosition: Player.PositionInfo,
//                reason: Int
//            ) {
//                updateProgress()
//            }
//        }
//
//        player.addListener(listener)
//
//        isPlaying = player.isPlaying
//        updateProgress()
//
//        onDispose {
//            player.removeListener(listener)
//        }
//    }
//
//    // ✅ Update progress while playing
//    LaunchedEffect(player) {
//        while (isActive) {
//            if (player.isPlaying) updateProgress()
//            delay(250)
//        }
//    }
//
//    val pickerLauncher = rememberLauncherForActivityResult(
//        contract = ActivityResultContracts.GetContent()
//    ) { uri ->
//        uri?.let {
//            player.setMediaItem(MediaItem.fromUri(it))
//            player.prepare()
//            player.play()
//        }
//    }
//
//    Column(
//        modifier = modifier.fillMaxSize().padding(16.dp),
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.spacedBy(8.dp)
//    ) {
//        Slider(
//            value = progress,
//            onValueChange = { newVal ->
//                val duration = player.duration
//                if (duration > 0 && duration != C.TIME_UNSET) {
//                    player.seekTo((newVal * duration).toLong())
//                }
//            },
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        Text(
//            text = "${formatTime(player.currentPosition)} / ${formatTime(player.duration)}",
//            style = MaterialTheme.typography.bodyMedium,
//            color = Color.Gray
//        )
//
//        Button(onClick = { pickerLauncher.launch("audio/*") }) {
//            Text("Select File")
//        }
//
//        val controller = session.controller
//
//        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
//            Button(onClick = { controller.transportControls.skipToPrevious() }) {
//                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
//            }
//
//            Button(onClick = {
//                if (isPlaying) controller.transportControls.pause()
//                else controller.transportControls.play()
//            }) {
//                Icon(
//                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
//                    contentDescription = if (isPlaying) "Pause" else "Play"
//                )
//            }
//
//            Button(onClick = { controller.transportControls.skipToNext() }) {
//                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
//            }
//        }
//    }
//}
//
//private fun formatTime(ms: Long): String {
//    if (ms <= 0 || ms == C.TIME_UNSET) return "00:00"
//    val totalSeconds = ms / 1000
//    val minutes = totalSeconds / 60
//    val seconds = totalSeconds % 60
//    return "%02d:%02d".format(minutes, seconds)
//}
