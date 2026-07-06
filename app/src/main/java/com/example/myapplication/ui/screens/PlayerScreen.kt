package com.example.myapplication.ui.screens

import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.MainViewModel
import com.example.myapplication.R
import com.example.myapplication.Song
import com.example.myapplication.ui.components.SongRow
import com.example.myapplication.ui.components.TransportControls
import kotlinx.coroutines.delay

/**
 * The main player screen: library list, expandable EQ/visualizer/queue pager,
 * now-playing card with seekbar and transport controls, and the add-to-playlist
 * dialog. Screen-level composable: reads state from [viewModel] and reaches the
 * Activity for the three system-picker actions via [onPickFolder]/[onExport]/
 * [onImport]. Leaf UI is delegated to the stateless components/screens.
 */
@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onPickFolder: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    val playbackConnection = viewModel.playbackConnection
    val equalizerController = viewModel.equalizerController
    val playlists = viewModel.playlists

    val songs = viewModel.songs.collectAsState().value
    val currentTitle = playbackConnection.nowPlayingTitle.collectAsState().value ?: "No song selected"
    val currentId = playbackConnection.nowPlayingId.collectAsState().value
    val playing = playbackConnection.isPlaying.collectAsState().value
    val shuffled = playbackConnection.isShuffled.collectAsState().value
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    var isScrubbing by rememberSaveable { mutableStateOf(false) }
    var scrubPositionMs by rememberSaveable { mutableStateOf(0L) }
    val durationMs = playbackConnection.duration.collectAsState().value
    val positionMs = playbackConnection.position.collectAsState().value
    val effectivePosition = if (isScrubbing) scrubPositionMs else positionMs
    var playlistTargetSong by remember { mutableStateOf<Song?>(null) }
    var newPlaylistName by rememberSaveable { mutableStateOf("") }
    val showPlaylistDialog = playlistTargetSong != null

    LaunchedEffect(playing, isScrubbing) {
        while (playing && !isScrubbing) {
            playbackConnection.refreshPosition()
            delay(1000L)
        }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF120C09),
            Color(0xFF1B120E),
            Color(0xFF2A1B13)
        )
    )
    val panelColor = Color(0xFF2A1A14)
    val panelBorder = Color(0xFF4B2C1F)
    val panelGlow = Color(0xFFB86A2C)
    val textWarm = Color(0xFFE6C7A1)
    val textMuted = Color(0xFFB08A63)
    val iconGlow = Color(0xFFFFB14A)
    val dividerWarm = Color(0xFF3C2419)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Music Monster",
                style = MaterialTheme.typography.headlineMedium,
                color = textWarm,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Music",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm,
                    modifier = Modifier
                        .clickable { onPickFolder() }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (songs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No songs found on this device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        itemsIndexed(songs) { index, song ->
                            SongRow(
                                title = song.title,
                                durationLabel = formatTime(song.durationMs),
                                isCurrent = song.id == currentId,
                                textWarm = textWarm,
                                textMuted = textMuted,
                                accent = iconGlow,
                                onClick = { playbackConnection.playFromMediaId(song.id) },
                                onLongClick = { playlistTargetSong = song }
                            )

                            if (index < songs.lastIndex) {
                                Divider(color = dividerWarm)
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (pagerState.currentPage == 1) {
                                    Color.Transparent
                                } else {
                                    Color(0xFF1B110C).copy(alpha = 0.95f)
                                }
                            )
                    ) {
                        if (pagerState.currentPage == 1) {
                            Image(
                                painter = painterResource(id = R.drawable.background_screen2),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) { page ->
                                    when (page) {
                                        0 -> EqualizerPanel(
                                            audioSessionId = equalizerController.audioSessionId.value,
                                            textWarm = textWarm,
                                            textMuted = textMuted,
                                            accent = iconGlow,
                                            equalizer = equalizerController.equalizer,
                                            bassBoost = equalizerController.bassBoost,
                                            eqEnabled = equalizerController.eqEnabled.value,
                                            onEqEnabledChanged = { equalizerController.eqEnabled.value = it },
                                            eqBandLevels = equalizerController.eqBandLevels,
                                            eqBandCount = equalizerController.eqBandCount,
                                            eqBandHz = equalizerController.eqBandHz,
                                            bassBoostEnabled = equalizerController.bassBoostEnabled.value,
                                            onBassBoostEnabled = { equalizerController.bassBoostEnabled.value = it },
                                            bassBoostStrength = equalizerController.bassBoostStrength.value,
                                            onBassBoostStrength = { equalizerController.bassBoostStrength.value = it },
                                            presetLabel = equalizerController.eqPresetLabel.value,
                                            buildPresetLevels = { label, eq ->
                                                equalizerController.buildPresetLevels(label, eq)
                                            },
                                            onPresetSelected = { label, levels ->
                                                equalizerController.eqPresetLabel.value = label
                                                if (levels.isNotEmpty()) {
                                                    equalizerController.eqBandLevels.clear()
                                                    equalizerController.eqBandLevels.addAll(levels)
                                                    equalizerController.eqBandCount.value = levels.size
                                                    val eq = equalizerController.equalizer
                                                    if (eq != null) {
                                                        val range = eq.bandLevelRange
                                                        for (bandIndex in levels.indices) {
                                                            val band = bandIndex.toShort()
                                                            val level = levels[bandIndex].toShort()
                                                            eq.setBandLevel(
                                                                band,
                                                                level.coerceIn(range[0], range[1])
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        1 -> VisualizerPanel(
                                            audioSessionId = equalizerController.audioSessionId.value,
                                            textWarm = textWarm,
                                            accent = iconGlow
                                        )
                                        else -> QueuePanel(
                                            songs = songs,
                                            playlists = playlists,
                                            textWarm = textWarm,
                                            textMuted = textMuted,
                                            accent = iconGlow,
                                            formatRemaining = { formatHms(it) },
                                            onStartTimer = { durationMs, fadeMs ->
                                                viewModel.startSleepTimer(durationMs, fadeMs)
                                            },
                                            onCancelTimer = { viewModel.cancelSleepTimer() },
                                            onPlayPlaylist = { playlist, startId ->
                                                viewModel.playPlaylist(playlist, startId)
                                            },
                                            onSavePlaylists = { viewModel.savePlaylists() },
                                            onCreatePlaylist = { name, initial ->
                                                viewModel.createPlaylist(name, initial)
                                            },
                                            onAddSongToPlaylist = { playlist, song ->
                                                viewModel.addSongToPlaylist(playlist, song)
                                            },
                                            onExport = onExport,
                                            onImport = onImport
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                repeat(3) { index ->
                                    val isSelected = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 6.dp)
                                            .height(6.dp)
                                            .width(if (isSelected) 26.dp else 12.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                if (isSelected) {
                                                    iconGlow
                                                } else {
                                                    dividerWarm
                                                }
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = panelColor
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .border(1.dp, panelBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = textWarm,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (playing) "Playing" else "Paused",
                                style = MaterialTheme.typography.labelMedium,
                                color = textMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) {
                                    Icons.Default.KeyboardArrowDown
                                } else {
                                    Icons.Default.KeyboardArrowUp
                                },
                                contentDescription = "Expand",
                                tint = iconGlow
                            )
                        }
                    }

                    if (durationMs > 0L) {
                        Slider(
                            value = (effectivePosition / durationMs.toFloat())
                                .coerceIn(0f, 1f),
                            onValueChange = { value ->
                                isScrubbing = true
                                scrubPositionMs = (durationMs * value).toLong()
                            },
                            onValueChangeFinished = {
                                playbackConnection.seekTo(scrubPositionMs)
                                isScrubbing = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = iconGlow,
                                activeTrackColor = iconGlow,
                                inactiveTrackColor = panelBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(effectivePosition),
                                style = MaterialTheme.typography.labelSmall,
                                color = textWarm
                            )
                            Text(
                                text = formatTime(durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = textWarm
                            )
                        }

                        val nextSong = remember(songs, currentId) {
                            viewModel.nextUpSong(songs, currentId)
                        }
                        if (nextSong != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Next up:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textMuted,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = nextSong.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textWarm,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    TransportControls(
                        isPlaying = playing,
                        isShuffled = shuffled,
                        accent = iconGlow,
                        shuffleActiveColor = panelGlow,
                        shuffleInactiveColor = textMuted,
                        onPrevious = { playbackConnection.skipToPrevious() },
                        onPlayPause = {
                            if (playing) {
                                playbackConnection.pause()
                            } else {
                                if (currentId == null && songs.isNotEmpty()) {
                                    playbackConnection.playFromMediaId(songs.first().id)
                                } else {
                                    playbackConnection.play()
                                }
                            }
                        },
                        onNext = { playbackConnection.skipToNext() },
                        onToggleShuffle = {
                            val newMode = if (shuffled) {
                                PlaybackStateCompat.SHUFFLE_MODE_NONE
                            } else {
                                PlaybackStateCompat.SHUFFLE_MODE_ALL
                            }
                            playbackConnection.setShuffleMode(newMode)
                        }
                    )
                }
            }

            if (showPlaylistDialog) {
                AlertDialog(
                    onDismissRequest = {
                        playlistTargetSong = null
                        newPlaylistName = ""
                    },
                    title = {
                        Text(
                            text = "Add to Playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = textWarm
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playlists.isEmpty()) {
                                Text(
                                    text = "No playlists yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textMuted
                                )
                            } else {
                                playlists.forEach { playlist ->
                                    Button(
                                        onClick = {
                                            val song = playlistTargetSong
                                            if (song != null) {
                                                viewModel.addSongToPlaylist(playlist, song)
                                            }
                                            playlistTargetSong = null
                                            newPlaylistName = ""
                                        }
                                    ) {
                                        Text(text = playlist.name, color = textWarm)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it.take(24) },
                                label = { Text("New playlist", color = textMuted) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = iconGlow,
                                    focusedLabelColor = iconGlow,
                                    unfocusedBorderColor = textMuted,
                                    unfocusedLabelColor = textMuted,
                                    cursorColor = iconGlow
                                ),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val name = newPlaylistName.trim()
                            if (name.isNotEmpty()) {
                                viewModel.createPlaylist(name, playlistTargetSong)
                            }
                            playlistTargetSong = null
                            newPlaylistName = ""
                        }) {
                            Text("Create", color = textWarm)
                        }
                    },
                    dismissButton = {
                        Button(onClick = {
                            playlistTargetSong = null
                            newPlaylistName = ""
                        }) {
                            Text("Close", color = textWarm)
                        }
                    }
                )
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

private fun formatHms(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d:%02d", hours, minutes, seconds)
}
