package com.nomesame.musicmonster.ui.screens

import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.MainViewModel
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.ui.components.AccentPickerDialog
import com.nomesame.musicmonster.ui.components.AppBackground
import com.nomesame.musicmonster.ui.components.FastScroller
import com.nomesame.musicmonster.ui.components.rememberBackgroundBitmap
import com.nomesame.musicmonster.ui.components.SongRow
import com.nomesame.musicmonster.ui.components.TransportControls
import com.nomesame.musicmonster.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onPickFolder: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAccentChange: (Color) -> Unit,
    onPickBackground: () -> Unit
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
    var isScrubbing by rememberSaveable { mutableStateOf(false) }
    var scrubPositionMs by rememberSaveable { mutableStateOf(0L) }
    val durationMs = playbackConnection.duration.collectAsState().value
    val positionMs = playbackConnection.position.collectAsState().value
    val effectivePosition = if (isScrubbing) scrubPositionMs else positionMs
    var playlistTargetSong by remember { mutableStateOf<Song?>(null) }
    var newPlaylistName by rememberSaveable { mutableStateOf("") }
    val showPlaylistDialog = playlistTargetSong != null
    var showAccentPicker by remember { mutableStateOf(false) }
    val swipePagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val listState = rememberLazyListState()

    LaunchedEffect(playing, isScrubbing) {
        while (playing && !isScrubbing) {
            playbackConnection.refreshPosition()
            delay(1000L)
        }
    }

    // On first open, scroll the list to the restored/current song so it doesn't
    // sit at the top after the app was fully closed and reopened.
    var didInitialScroll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentId, songs) {
        if (!didInitialScroll && currentId != null && songs.isNotEmpty()) {
            val idx = songs.indexOfFirst { it.id == currentId }
            if (idx >= 0) listState.scrollToItem(idx)
            didInitialScroll = true
        }
    }

    val appColors = LocalAppColors.current
    val backgroundBrush = Brush.verticalGradient(appColors.backgroundGradient)

    // ONE full-screen background is drawn at the root (image+scrim when the
    // custom toggle is on, otherwise the gradient) and every layer above it is
    // transparent, so the list and the swipe-screens share the exact same
    // background with no seams. The list occludes the swipe-screens not with its
    // own backing but by only drawing them in the pulled-open strip (see below).
    val customBgEnabled = viewModel.customBgEnabled.collectAsState().value
    val customBgUri = viewModel.customBgUri.collectAsState().value
    val customBgScrim = viewModel.customBgScrim.collectAsState().value
    val pendingPaletteAccent = viewModel.pendingPaletteAccent.collectAsState().value
    val customBgBitmap = rememberBackgroundBitmap(if (customBgEnabled) customBgUri else null)
    val useDefaultBgImage = customBgEnabled && customBgUri == null
    // 0 = list covers everything, 1 = list fully pulled down (screens revealed).
    val pullProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        label = "listPull"
    )
    val panelColor = appColors.panel
    val panelBorder = appColors.panelBorder
    val panelGlow = appColors.accentSoft
    val textWarm = appColors.textPrimary
    val textMuted = appColors.textMuted
    val iconGlow = appColors.accent
    val dividerWarm = appColors.divider

    Box(modifier = Modifier.fillMaxSize()) {
      // The single full-screen background shared by every layer.
      if (customBgEnabled) {
          AppBackground(
              bitmap = customBgBitmap,
              useDefaultImage = useDefaultBgImage,
              scrim = customBgScrim
          )
      } else {
          Box(Modifier.fillMaxSize().background(backgroundBrush))
      }
      Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Music Monster",
                    style = MaterialTheme.typography.headlineMedium,
                    color = textWarm,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showAccentPicker = true }) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Accent color",
                        tint = iconGlow
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Open folder",
                    tint = iconGlow,
                    modifier = Modifier.clickable { onPickFolder() }.padding(end = 8.dp)
                )
                Text(
                    text = "Music",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm,
                    modifier = Modifier.clickable { onPickFolder() }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                // BEHIND: the 3 swipe-screens (Sleep · Equalizer · Playlists).
                // Drawn only within the top strip that the list has pulled open,
                // so they never bleed through the (transparent) list above them.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(bottom = pullProgress * size.height) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    HorizontalPager(
                        state = swipePagerState,
                        // Full height (no top padding) so the empty area above the
                        // titles is still part of the pager and reacts to swipes.
                        // The shared 80dp title offset lives in each page's content.
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { page ->
                        when (page) {
                            0 -> Box(modifier = Modifier.fillMaxSize().padding(top = 80.dp)) {
                              PlaylistScreen(
                                songs = songs,
                                playlists = playlists,
                                textWarm = textWarm,
                                textMuted = textMuted,
                                accent = iconGlow,
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
                            1 -> Box(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 16.dp)) {
                                EqualizerPanel(
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
                                                        eq.setBandLevel(band, level.coerceIn(range[0], range[1]))
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                else -> Box(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 16.dp)) {
                                    SleepTimerPanel(
                                        textWarm = textWarm,
                                        textMuted = textMuted,
                                        accent = iconGlow,
                                        formatRemaining = { formatHms(it) },
                                        onStartTimer = { d, f -> viewModel.startSleepTimer(d, f) },
                                        onCancelTimer = { viewModel.cancelSleepTimer() }
                                    )
                                }
                            }
                        }

                        // Page indicator for the 3 swipe-screens.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            repeat(3) { index ->
                                val selected = swipePagerState.currentPage == index
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (selected) 9.dp else 7.dp)
                                        .background(
                                            if (selected) iconGlow else dividerWarm,
                                            RoundedCornerShape(999.dp)
                                        )
                                )
                            }
                        }
                }

                // FRONT: the song list. Transparent (the root background shows
                // through); it just slides down to uncover the swipe-screens,
                // which are only drawn in the strip it uncovers.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = pullProgress * size.height }
                ) {
                    if (songs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No songs found on this device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
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

                        FastScroller(
                            lazyListState = listState,
                            songs = songs,
                            modifier = Modifier.fillMaxSize(),
                            accent = iconGlow,
                            textWarm = textWarm,
                            textMuted = textMuted
                        )
                    }
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor),
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
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) "Show list" else "Show equalizer / timer / playlists",
                                tint = iconGlow
                            )
                        }
                    }

                    if (durationMs > 0L) {
                        Slider(
                            value = (effectivePosition / durationMs.toFloat()).coerceIn(0f, 1f),
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
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
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
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
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
                                        Text(text = playlist.name)
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
                            Text("Create")
                        }
                    },
                    dismissButton = {
                        Button(onClick = {
                            playlistTargetSong = null
                            newPlaylistName = ""
                        }) {
                            Text("Close")
                        }
                    }
                )
            }

            if (showAccentPicker) {
                AccentPickerDialog(
                    current = appColors.accent,
                    onAccentChange = onAccentChange,
                    onDismiss = { showAccentPicker = false },
                    customBgEnabled = customBgEnabled,
                    onCustomBgEnabledChange = { viewModel.setCustomBgEnabled(it) },
                    hasCustomBgImage = customBgUri != null,
                    bgScrim = customBgScrim,
                    onBgScrimChange = { viewModel.setCustomBgScrim(it) },
                    onPickBackground = onPickBackground,
                    onResetBackground = { viewModel.resetCustomBackground() }
                )
            }

            // After picking an image, offer to adopt a matching accent theme.
            if (pendingPaletteAccent != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissPendingPalette() },
                    containerColor = panelColor,
                    title = { Text(text = "Match theme to image?", color = textWarm) },
                    text = {
                        Text(
                            text = "Load an accent color that fits your new background?",
                            color = textMuted
                        )
                    },
                    confirmButton = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(pendingPaletteAccent)
                            )
                            Spacer(Modifier.size(8.dp))
                            Button(onClick = { viewModel.applyPendingPalette() }) {
                                Text("Use it")
                            }
                        }
                    },
                    dismissButton = {
                        Button(onClick = { viewModel.dismissPendingPalette() }) {
                            Text("Keep current")
                        }
                    }
                )
            }
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