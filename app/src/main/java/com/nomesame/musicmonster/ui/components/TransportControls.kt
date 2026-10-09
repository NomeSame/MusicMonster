package com.nomesame.musicmonster.ui.components

import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Previous / play-pause / next / shuffle transport row. Stateless: the caller
 * decides what play-pause and shuffle-toggle do.
 */
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
    onToggleShuffle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.previous),
                tint = accent
            )
        }

        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                tint = accent
            )
        }

        IconButton(onClick = onNext) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.next),
                tint = accent
            )
        }

        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                tint = if (isShuffled) shuffleActiveColor else shuffleInactiveColor,
                contentDescription = stringResource(R.string.shuffle)
            )
        }
    }
}

@Preview
@Composable
private fun TransportControlsPreview() {
    TransportControls(
        isPlaying = true,
        isShuffled = false,
        accent = Color(0xFF80DEEA),
        shuffleActiveColor = Color(0xFF80DEEA),
        shuffleInactiveColor = Color.Gray,
        onPrevious = {},
        onPlayPause = {},
        onNext = {},
        onToggleShuffle = {}
    )
}
