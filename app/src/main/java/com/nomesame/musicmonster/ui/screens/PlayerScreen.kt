package com.nomesame.musicmonster.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.ui.components.ControlLabel
import com.nomesame.musicmonster.ui.components.SongSelectionBar
import com.nomesame.musicmonster.ui.components.SelectPlaylistDialog
import com.nomesame.musicmonster.ui.components.CreatePlaylistDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
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
import com.nomesame.musicmonster.MusicLogic
import com.nomesame.musicmonster.ui.components.AccentPickerDialog
import com.nomesame.musicmonster.ui.components.ArtworkCropDialog
import com.nomesame.musicmonster.ui.components.AppSettingsButton
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
    val currentTitle = playbackConnection.nowPlayingTitle.collectAsState().value ?: stringResource(R.string.no_song_selected)
    val currentId = playbackConnection.nowPlayingId.collectAsState().value
    val playing = playbackConnection.isPlaying.collectAsState().value
    val shuffled = playbackConnection.isShuffled.collectAsState().value
    var expanded by rememberSaveable { mutableStateOf(false) }
    var isScrubbing by rememberSaveable { mutableStateOf(false) }
    var scrubPositionMs by rememberSaveable { mutableStateOf(0L) }
    val durationMs = playbackConnection.duration.collectAsState().value
    val positionMs = playbackConnection.position.collectAsState().value
    val effectivePosition = if (isScrubbing) scrubPositionMs else positionMs
    val selection by viewModel.songSelection.state.collectAsState()
    val targetPlaylist = playlists.firstOrNull { it.id == selection.targetPlaylistId }
    val targetEntries = targetPlaylist?.songIds?.toList().orEmpty()
    val existingCounts = remember(targetEntries) { targetEntries.groupingBy { it }.eachCount() }
    var selectionDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var newPlaylistName by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = selection.active) {
        selectionDialog = null
        if (selection.targetPlaylistId != null) expanded = true
        viewModel.songSelection.finish()
    }
    var showAccentPicker by rememberSaveable { mutableStateOf(false) }
    var showArtworkCrop by rememberSaveable { mutableStateOf(false) }
    val artworkCrop by viewModel.artworkCrop.position.collectAsState()
    val artworkPreview by viewModel.artworkCrop.preview.collectAsState()
    val artworkPreviewLoading by viewModel.artworkCrop.loading.collectAsState()
    DisposableEffect(showArtworkCrop) {
        if (showArtworkCrop) viewModel.artworkCrop.openPreview()
        onDispose { viewModel.artworkCrop.closePreview() }
    }
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
    val playerOpacity by viewModel.playerOpacity.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
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
      // Falls back to the gradient when the custom image failed to load.
      if (customBgEnabled && (customBgBitmap != null || useDefaultBgImage)) {
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
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = textWarm,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showAccentPicker = true }) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = stringResource(R.string.accent_color),
                        tint = iconGlow
                    )
                }
                AppSettingsButton(appLanguage, viewModel::setAppLanguage)
            }

            AnimatedContent(
                targetState = selection.active,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize(),
                label = "librarySelection"
            ) { selecting ->
                if (selecting) {
                    SongSelectionBar(
                        count = selection.ids.size,
                        allSelected = selection.allSelected(songs.map { it.id }),
                        hasPlaylists = if (selection.targetPlaylistId != null) targetPlaylist != null else playlists.isNotEmpty(),
                        onAdd = {
                            if (targetPlaylist != null) {
                                if (viewModel.addSelectionToPlaylist(targetPlaylist)) expanded = true
                            } else selectionDialog = "add"
                        },
                        onCreate = { newPlaylistName = ""; selectionDialog = "create" },
                        onToggleAll = { viewModel.songSelection.toggleAll(songs.map { it.id }) },
                        onClose = {
                            selectionDialog = null
                            if (selection.targetPlaylistId != null) expanded = true
                            viewModel.songSelection.finish()
                        },
                        targetPlaylistName = targetPlaylist?.name
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPickFolder, modifier = Modifier.testTag("folder_picker")) {
                            Icon(Icons.Default.Folder, stringResource(R.string.open_folder), tint = iconGlow)
                        }
                        Text(stringResource(R.string.music), color = textWarm,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable(onClick = onPickFolder))
                    }
                }
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
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("panel_pager")
                    ) { page ->
                        when (page) {
                            0 -> Box(modifier = Modifier.fillMaxSize().padding(top = 80.dp)) {
                              PlaylistScreen(
                                songs = songs,
                                playlists = playlists,
                                textWarm = textWarm,
                                textMuted = textMuted,
                                accent = iconGlow,
                                onPlayPlaylist = { playlist, startId, startIndex ->
                                    viewModel.playPlaylist(playlist, startId, startIndex)
                                },
                                onSavePlaylists = { viewModel.savePlaylists() },
                                onCreatePlaylist = { name, initial ->
                                    viewModel.createPlaylist(name, initial)
                                },
                                onAddSongs = { playlist ->
                                    if (viewModel.startPlaylistSelection(playlist)) expanded = false
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
                                        formatRemaining = { MusicLogic.formatTime(it) },
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
                                text = stringResource(R.string.no_songs_found),
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
                            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                                SongRow(
                                    title = song.title,
                                    durationLabel = MusicLogic.formatTime(song.durationMs),
                                    isCurrent = song.id == currentId,
                                    textWarm = textWarm,
                                    textMuted = textMuted,
                                    accent = iconGlow,
                                    onClick = {
                                        if (selection.active) viewModel.songSelection.toggle(song.id)
                                        else playbackConnection.playFromMediaId(song.id)
                                    },
                                    onLongClick = {
                                        if (selection.active) viewModel.songSelection.toggle(song.id)
                                        else viewModel.songSelection.start(song.id)
                                    },
                                    selectionMode = selection.active,
                                    isSelected = song.id in selection.ids,
                                    existingCount = existingCounts[song.id] ?: 0,
                                    modifier = Modifier.testTag("song_row_" + song.id)
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
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor.copy(alpha = playerOpacity)),
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
                                text = if (playing) stringResource(R.string.playing) else stringResource(R.string.paused),
                                style = MaterialTheme.typography.labelMedium,
                                color = textMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        IconButton(onClick = { expanded = !expanded }, enabled = !selection.active,
                            modifier = Modifier.testTag("player_expand")) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) stringResource(R.string.show_list) else stringResource(R.string.show_panels),
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
                                text = MusicLogic.formatTime(effectivePosition),
                                style = MaterialTheme.typography.labelSmall,
                                color = textWarm
                            )
                            Text(
                                text = MusicLogic.formatTime(durationMs),
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
                                    text = stringResource(R.string.next_up),
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

            if (selection.active && selectionDialog == "add") {
                SelectPlaylistDialog(
                    playlists = playlists,
                    onSelect = { playlist ->
                        if (viewModel.addSelectionToPlaylist(playlist)) selectionDialog = null
                    },
                    onDismiss = { selectionDialog = null }
                )
            }
            if (selection.active && selectionDialog == "create") {
                CreatePlaylistDialog(
                    name = newPlaylistName,
                    onNameChange = { newPlaylistName = it },
                    onConfirm = {
                        if (viewModel.createPlaylistFromSelection(newPlaylistName)) {
                            selectionDialog = null
                            newPlaylistName = ""
                        }
                    },
                    onDismiss = { selectionDialog = null }
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
                    onResetBackground = { viewModel.resetCustomBackground() },
                    playerOpacity = playerOpacity,
                    onPlayerOpacityChange = { viewModel.setPlayerOpacity(it) },
                    onEditArtworkCrop = { showArtworkCrop = true }
                )
            }

            if (showArtworkCrop) {
                ArtworkCropDialog(artworkPreview, artworkPreviewLoading, artworkCrop, customBgScrim,
                    viewModel.artworkCrop::setPosition, { showArtworkCrop = false })
            }

            // After picking an image, offer to adopt a matching accent theme.
            if (pendingPaletteAccent != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissPendingPalette() },
                    containerColor = panelColor,
                    title = { Text(text = stringResource(R.string.match_theme), color = textWarm) },
                    text = {
                        Text(
                            text = stringResource(R.string.match_theme_description),
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
                                ControlLabel(stringResource(R.string.use_color))
                            }
                        }
                    },
                    dismissButton = {
                        Button(onClick = { viewModel.dismissPendingPalette() }) {
                            ControlLabel(stringResource(R.string.keep_color))
                        }
                    }
                )
            }
        }
      }
    }
}

