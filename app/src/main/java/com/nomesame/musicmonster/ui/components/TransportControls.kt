package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.SeekStep

/** Stateless transport row. Narrow layouts retain full-size touch targets. */
@Composable
fun TransportControls(
    isPlaying: Boolean,
    isShuffled: Boolean,
    accent: Color,
    shuffleActiveColor: Color,
    shuffleInactiveColor: Color,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    seekSeconds: Int = SeekStep.DEFAULT_SECONDS,
    canSeek: Boolean = false,
    onRewind: () -> Unit = {},
    onFastForward: () -> Unit = {},
    showSeekButtons: Boolean = true
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val narrow = maxWidth < 240.dp
        val separateShuffle = maxWidth < 288.dp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                if (!narrow) SeekJumpSlot(showSeekButtons, false, seekSeconds, canSeek, accent, onRewind)
                IconButton(onClick = onPrevious, modifier = Modifier.testTag("transport_previous")) {
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), tint = accent)
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag("transport_play_pause")) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (isPlaying) R.string.pause else R.string.play), tint = accent)
                }
                IconButton(onClick = onNext, modifier = Modifier.testTag("transport_next")) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.next), tint = accent)
                }
                if (!narrow) SeekJumpSlot(showSeekButtons, true, seekSeconds, canSeek, accent, onFastForward)
                if (!separateShuffle) ShuffleButton(isShuffled, shuffleActiveColor,
                    shuffleInactiveColor, onToggleShuffle)
            }
            if (separateShuffle) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (narrow) SeekJumpSlot(showSeekButtons, false, seekSeconds, canSeek, accent, onRewind)
                    ShuffleButton(isShuffled, shuffleActiveColor, shuffleInactiveColor, onToggleShuffle)
                    if (narrow) SeekJumpSlot(showSeekButtons, true, seekSeconds, canSeek, accent, onFastForward)
                }
            }
        }
    }
}

@Composable
private fun SeekJumpSlot(visible: Boolean, forward: Boolean, seconds: Int,
    enabled: Boolean, accent: Color, onClick: () -> Unit) {
    if (visible) SeekJumpButton(forward, seconds, enabled, accent, onClick)
    else Spacer(Modifier.size(48.dp))
}

@Composable
private fun SeekJumpButton(forward: Boolean, seconds: Int, enabled: Boolean,
    accent: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.testTag(if (forward) "transport_forward" else "transport_rewind")) {
        Icon(Icons.Default.Replay,
            contentDescription = stringResource(if (forward) R.string.seek_forward else R.string.seek_backward, seconds),
            tint = if (enabled) accent else accent.copy(alpha = 0.38f),
            modifier = Modifier.graphicsLayer { scaleX = if (forward) -1f else 1f })
    }
}

@Composable
private fun ShuffleButton(active: Boolean, activeColor: Color, inactiveColor: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("transport_shuffle")) {
        Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle),
            tint = if (active) activeColor else inactiveColor)
    }
}
