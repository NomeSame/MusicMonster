package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Divider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.app.ActivityCompat
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat

import android.os.Handler
import android.os.Looper
import android.content.ContentUris
import androidx.compose.ui.graphics.Brush
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay


/**
 * Main activity that displays a list of audio files from the device and provides playback controls.
 */
class MainActivity : ComponentActivity() {

    // ✅ Make songs observable by Compose
    private var songs by mutableStateOf<List<Song>>(emptyList())

    // MediaController for interacting with the foreground service.
    private lateinit var mediaController: MediaControllerCompat

    // Compose state that reflects current playback status and title.
    private val nowPlayingTitle = mutableStateOf<String?>(null)
    private val nowPlayingId = mutableStateOf<String?>(null)
    private val isPlaying = mutableStateOf(false)
    private val isShuffled = mutableStateOf(false)
    private val playbackPositionMs = mutableStateOf(0L)
    private val playbackDurationMs = mutableStateOf(0L)
    private val audioSessionId = mutableStateOf(0)
    private val eqEnabled = mutableStateOf(true)
    private val eqBandLevels = mutableStateListOf<Int>()
    private val eqBandCount = mutableStateOf(0)
    private val eqBandHz = mutableStateListOf<Int>()
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private val bassBoostEnabled = mutableStateOf(false)
    private val bassBoostStrength = mutableStateOf(600)
    private val eqPresetLabel = mutableStateOf("Flat")
    private var controllerReady by mutableStateOf(false)
    private var serviceStarted = false


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadSongs()
            startMusicService()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestRecordAudioPermissionIfNeeded() {
        val granted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAudioPermissionAndLoad()
        requestNotificationPermissionIfNeeded()
        requestRecordAudioPermissionIfNeeded()

        setContent {
            MyApplicationTheme {
                if (controllerReady) {
                    MainScreen()
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        // Initialize the MediaController once the service has created its session.
        initMediaController()
    }

    override fun onResume() {
        super.onResume()
        // Ensure permission is still granted
        requestAudioPermissionAndLoad()
    }

    private fun requestAudioPermissionAndLoad() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(permission)
        } else {
            if (songs.isEmpty()) {
                loadSongs()
            }
            startMusicService()
        }
    }

    private fun startMusicService() {
        val intent = Intent(this, MusicService::class.java)
        if (!serviceStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceStarted = true
        } else {
            intent.action = MusicService.ACTION_RELOAD_LIBRARY
            startService(intent)
        }
    }

    private fun loadSongs() {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val list = mutableListOf<Song>()

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (c.moveToNext()) {
                val idLong = c.getLong(idCol)
                val title = c.getString(titleCol) ?: "Unknown"
                val durationMs = c.getLong(durationCol)

                val contentUri = ContentUris.withAppendedId(collection, idLong)

                list.add(
                    Song(
                        id = idLong.toString(),
                        title = title,
                        uri = contentUri,
                        durationMs = durationMs
                    )
                )
            }
        }

        songs = list
    }

    /**
     * Sets up a {@link MediaControllerCompat} to communicate with the foreground
     * music service. The controller is only created once the service has exposed its
     * session token.
     */
    private fun initMediaController() {
        // Use a Handler to poll for the session token until it becomes available.
        val handler = Handler(Looper.getMainLooper())

        val checkToken = object : Runnable {
            override fun run() {
                val token = MusicService.sessionToken
                if (token != null) {
                    mediaController = MediaControllerCompat(this@MainActivity, token)

                    mediaController.registerCallback(object : MediaControllerCompat.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                            isPlaying.value = state?.state == PlaybackStateCompat.STATE_PLAYING
                            playbackPositionMs.value = state?.position ?: 0L
                        }

                        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                            nowPlayingTitle.value =
                                metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                            nowPlayingId.value =
                                metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                            playbackDurationMs.value =
                                metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
                        }

                        override fun onShuffleModeChanged(shuffleMode: Int) {
                            isShuffled.value = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
                        }

                        override fun onExtrasChanged(extras: Bundle?) {
                            val id = extras?.getInt("audio_session_id") ?: 0
                            if (id != 0) {
                                audioSessionId.value = id
                                setupEqualizerForSession(id)
                            }
                        }
                    })

                    isShuffled.value = mediaController.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
                    nowPlayingTitle.value = mediaController.metadata
                        ?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                    nowPlayingId.value = mediaController.metadata
                        ?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                    playbackDurationMs.value = mediaController.metadata
                        ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
                    playbackPositionMs.value = mediaController.playbackState?.position ?: 0L
                    audioSessionId.value = mediaController.extras?.getInt("audio_session_id") ?: 0
                    if (audioSessionId.value != 0) {
                        setupEqualizerForSession(audioSessionId.value)
                    }
                    controllerReady = true // ✅ put this here (triggers recomposition)
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(checkToken)

    }

    @Composable
    fun MainScreen() {
        val currentTitle = nowPlayingTitle.value ?: "No song selected"
        val currentId = nowPlayingId.value
        val playing = isPlaying.value
        val shuffled = isShuffled.value
        var expanded by rememberSaveable { mutableStateOf(false) }
        val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
        var isScrubbing by rememberSaveable { mutableStateOf(false) }
        var scrubPositionMs by rememberSaveable { mutableStateOf(0L) }
        val durationMs = playbackDurationMs.value
        val positionMs = playbackPositionMs.value
        val effectivePosition = if (isScrubbing) scrubPositionMs else positionMs

        LaunchedEffect(playing, isScrubbing) {
            while (playing && !isScrubbing) {
                playbackPositionMs.value = mediaController.playbackState?.position ?: 0L
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
                        color = textWarm
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
                                val isCurrent = song.id == currentId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .clickable {
                                            mediaController.transportControls.playFromMediaId(
                                                song.id,
                                                null
                                            )
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = if (isCurrent) iconGlow else textMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isCurrent) textWarm else textMuted,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatTime(song.durationMs),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isCurrent) iconGlow else textMuted,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }

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
                                .background(Color(0xFF1B110C).copy(alpha = 0.95f))
                        ) {
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
                                                audioSessionId = audioSessionId.value,
                                                textWarm = textWarm,
                                                textMuted = textMuted,
                                                accent = iconGlow,
                                                eqEnabled = eqEnabled.value,
                                                onEqEnabledChanged = { eqEnabled.value = it },
                                                eqBandLevels = eqBandLevels,
                                                eqBandCount = eqBandCount,
                                                bassBoostEnabled = bassBoostEnabled.value,
                                                onBassBoostEnabled = { bassBoostEnabled.value = it },
                                                bassBoostStrength = bassBoostStrength.value,
                                                onBassBoostStrength = { bassBoostStrength.value = it },
                                                presetLabel = eqPresetLabel.value,
                                                onPresetSelected = { label, levels ->
                                                    eqPresetLabel.value = label
                                                    if (levels.isNotEmpty()) {
                                                        eqBandLevels.clear()
                                                        eqBandLevels.addAll(levels)
                                                        eqBandCount.value = levels.size
                                                        val eq = equalizer
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
                                                audioSessionId = audioSessionId.value,
                                                textWarm = textWarm,
                                                accent = iconGlow
                                            )
                                            else -> QueueAndSleepPanel(
                                                songs = songs,
                                                currentId = currentId,
                                                textWarm = textWarm,
                                                textMuted = textMuted,
                                                accent = iconGlow
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
                                    mediaController.transportControls.seekTo(scrubPositionMs)
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
                        }

                        val shuffleTint =
                            if (shuffled) panelGlow else textMuted

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                mediaController.transportControls.skipToPrevious()
                            }) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = iconGlow
                                )
                            }

                            IconButton(onClick = {
                                if (playing) {
                                    mediaController.transportControls.pause()
                                } else {
                                    if (currentId == null && songs.isNotEmpty()) {
                                        mediaController.transportControls.playFromMediaId(
                                            songs.first().id,
                                            null
                                        )
                                    } else {
                                        mediaController.transportControls.play()
                                    }
                                }
                            }) {
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = iconGlow
                                )
                            }

                            IconButton(onClick = {
                                mediaController.transportControls.skipToNext()
                            }) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Next",
                                    tint = iconGlow
                                )
                            }

                            IconButton(onClick = {
                                val newMode = if (shuffled) {
                                    PlaybackStateCompat.SHUFFLE_MODE_NONE
                                } else {
                                    PlaybackStateCompat.SHUFFLE_MODE_ALL
                                }
                                mediaController.transportControls.setShuffleMode(newMode)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    tint = shuffleTint,
                                    contentDescription = "Shuffle"
                                )
                            }
                        }
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

    private fun startSleepTimer(durationMs: Long, fadeMs: Long) {
        if (durationMs <= 0L) return
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_SET_SLEEP_TIMER
            putExtra(MusicService.EXTRA_SLEEP_MS, durationMs)
            putExtra(MusicService.EXTRA_FADE_MS, fadeMs)
        }
        startService(intent)
    }

    private fun cancelSleepTimer() {
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_CANCEL_SLEEP_TIMER
        }
        startService(intent)
    }

    @Composable
    private fun QueueAndSleepPanel(
        songs: List<Song>,
        currentId: String?,
        textWarm: Color,
        textMuted: Color,
        accent: Color
    ) {
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        val upNext = if (songs.isNotEmpty() && currentIndex >= 0) {
            val tail = songs.drop(currentIndex + 1)
            val head = songs.take(currentIndex)
            tail + head
        } else {
            emptyList()
        }
        var hoursText by rememberSaveable { mutableStateOf("") }
        var minutesText by rememberSaveable { mutableStateOf("") }
        var secondsText by rememberSaveable { mutableStateOf("") }
        var sleepTotalMs by rememberSaveable { mutableStateOf(0L) }
        var sleepRemainingMs by rememberSaveable { mutableStateOf(0L) }
        var sleepTargetElapsedMs by rememberSaveable { mutableStateOf<Long?>(null) }
        var timerRunning by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(sleepTargetElapsedMs, timerRunning) {
            if (sleepTargetElapsedMs == null || !timerRunning) return@LaunchedEffect
            while (timerRunning) {
                val remaining = (sleepTargetElapsedMs!! - SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                sleepRemainingMs = remaining
                if (remaining == 0L) {
                    timerRunning = false
                    break
                }
                delay(1000L)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (upNext.isEmpty()) {
                item {
                    Text(
                        text = "No upcoming tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textMuted,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            } else {
                itemsIndexed(upNext) { index, song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable {
                                mediaController.transportControls.playFromMediaId(song.id, null)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}. ${song.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textWarm,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatTime(song.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = textMuted,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sleep Timer",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        val totalMs = 15 * 60_000L
                        sleepTotalMs = totalMs
                        sleepRemainingMs = totalMs
                        sleepTargetElapsedMs = SystemClock.elapsedRealtime() + totalMs
                        timerRunning = true
                        startSleepTimer(totalMs, 10_000L)
                    }) {
                        Text("15m", color = textWarm)
                    }
                    Button(onClick = {
                        val totalMs = 30 * 60_000L
                        sleepTotalMs = totalMs
                        sleepRemainingMs = totalMs
                        sleepTargetElapsedMs = SystemClock.elapsedRealtime() + totalMs
                        timerRunning = true
                        startSleepTimer(totalMs, 10_000L)
                    }) {
                        Text("30m", color = textWarm)
                    }
                    Button(onClick = {
                        val totalMs = 60 * 60_000L
                        sleepTotalMs = totalMs
                        sleepRemainingMs = totalMs
                        sleepTargetElapsedMs = SystemClock.elapsedRealtime() + totalMs
                        timerRunning = true
                        startSleepTimer(totalMs, 10_000L)
                    }) {
                        Text("60m", color = textWarm)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hours", color = textMuted) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            focusedLabelColor = accent,
                            unfocusedBorderColor = textMuted,
                            unfocusedLabelColor = textMuted,
                            cursorColor = accent
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes", color = textMuted) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            focusedLabelColor = accent,
                            unfocusedBorderColor = textMuted,
                            unfocusedLabelColor = textMuted,
                            cursorColor = accent
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Seconds", color = textMuted) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            focusedLabelColor = accent,
                            unfocusedBorderColor = textMuted,
                            unfocusedLabelColor = textMuted,
                            cursorColor = accent
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = {
                        val h = hoursText.toLongOrNull() ?: 0L
                        val m = minutesText.toLongOrNull() ?: 0L
                        val s = secondsText.toLongOrNull() ?: 0L
                        val totalMs = (h * 3600 + m * 60 + s) * 1000L
                        if (totalMs > 0L) {
                            sleepTotalMs = totalMs
                            sleepRemainingMs = totalMs
                            sleepTargetElapsedMs = SystemClock.elapsedRealtime() + totalMs
                            timerRunning = true
                            startSleepTimer(totalMs, 10_000L)
                        }
                    }) {
                        Text("Start", color = textWarm)
                    }
                    Button(onClick = {
                        cancelSleepTimer()
                        timerRunning = false
                        sleepTargetElapsedMs = null
                        sleepRemainingMs = sleepTotalMs
                    }) {
                        Text("Cancel", color = textWarm)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (sleepTotalMs > 0L) {
                    val remainingLabel = formatHms(sleepRemainingMs)
                    Text(
                        text = if (timerRunning) "Time left: $remainingLabel" else "Set time: $remainingLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = textMuted
                    )
                }
            }
        }
    }

    private fun buildPresetLevels(label: String, equalizer: Equalizer): List<Int> {
        val bandCount = equalizer.numberOfBands.toInt()
        val range = equalizer.bandLevelRange
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val boost = (maxLevel * 0.75f).toInt()
        val mid = (maxLevel * 0.35f).toInt()
        val cut = (minLevel * 0.6f).toInt()

        val curve = when (label.lowercase()) {
            "metal" -> listOf(boost, mid, 0, mid, boost)
            "rock" -> listOf(mid, boost, mid, boost, mid)
            "classic" -> listOf(cut, 0, mid, mid, cut)
            "pop" -> listOf(0, mid, boost, mid, 0)
            "flat" -> listOf(0, 0, 0, 0, 0)
            else -> listOf(0, 0, 0, 0, 0)
        }

        if (equalizer.numberOfPresets > 0) {
            for (i in 0 until equalizer.numberOfPresets) {
                val preset = i.toShort()
                val name = equalizer.getPresetName(preset).lowercase()
                if (name.contains(label.lowercase())) {
                    equalizer.usePreset(preset)
                    return List(bandCount) { bandIndex ->
                        equalizer.getBandLevel(bandIndex.toShort()).toInt()
                    }
                }
            }
        }

        return List(bandCount) { bandIndex ->
            val idx = (bandIndex.toFloat() / (bandCount - 1).coerceAtLeast(1)).times(4).toInt()
                .coerceIn(0, 4)
            curve[idx].coerceIn(minLevel, maxLevel)
        }
    }

    private fun setupEqualizerForSession(sessionId: Int) {
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
        if (sessionId == 0) return
        try {
            val eq = Equalizer(0, sessionId)
            eq.enabled = eqEnabled.value
            val bandCount = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            if (eqBandLevels.size != bandCount || eqBandCount.value != bandCount) {
                eqBandLevels.clear()
                eqBandHz.clear()
                repeat(bandCount) { bandIndex ->
                    val band = bandIndex.toShort()
                    eqBandLevels.add(eq.getBandLevel(band).toInt())
                    eqBandHz.add((eq.getCenterFreq(band) / 1000).toInt())
                }
                eqBandCount.value = bandCount
            } else {
                for (bandIndex in 0 until bandCount) {
                    val band = bandIndex.toShort()
                    val level = eqBandLevels[bandIndex]
                    eq.setBandLevel(band, level.toShort().coerceIn(range[0], range[1]))
                }
            }
            equalizer = eq
        } catch (_: Throwable) {
            equalizer = null
        }

        try {
            val bb = BassBoost(0, sessionId)
            bb.enabled = bassBoostEnabled.value
            bb.setStrength(bassBoostStrength.value.toShort())
            bassBoost = bb
        } catch (_: Throwable) {
            bassBoost = null
        }
    }

    @Composable
    private fun VisualizerPanel(
        audioSessionId: Int,
        textWarm: Color,
        accent: Color
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Visualizer",
                style = MaterialTheme.typography.titleMedium,
                color = textWarm,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    CircularVisualizerView(context).apply {
                        setVisualizerColor(accent)
                    }
                },
                update = { view ->
                    view.setAudioSessionId(audioSessionId)
                },
                modifier = Modifier
                    .size(220.dp)
                    .padding(12.dp)
            )
        }
    }

    @Composable
    private fun EqualizerPanel(
        audioSessionId: Int,
        textWarm: Color,
        textMuted: Color,
        accent: Color,
        eqEnabled: Boolean,
        onEqEnabledChanged: (Boolean) -> Unit,
        eqBandLevels: MutableList<Int>,
        eqBandCount: MutableState<Int>,
        bassBoostEnabled: Boolean,
        onBassBoostEnabled: (Boolean) -> Unit,
        bassBoostStrength: Int,
        onBassBoostStrength: (Int) -> Unit,
        presetLabel: String,
        onPresetSelected: (String, List<Int>) -> Unit
    ) {
        val equalizer = equalizer

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Equalizer",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm
                )
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = {
                        onEqEnabledChanged(it)
                        equalizer?.enabled = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accent,
                        checkedTrackColor = accent.copy(alpha = 0.5f),
                        uncheckedThumbColor = textMuted,
                        uncheckedTrackColor = textMuted.copy(alpha = 0.4f)
                    )
                )
            }

            if (equalizer == null) {
                Text(
                    text = "Audio session not ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textMuted
                )
                return
            }

            Text(
                text = "Presets: $presetLabel",
                style = MaterialTheme.typography.labelMedium,
                color = textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val presets = listOf("Metal", "Rock", "Classic", "Flat", "Pop")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.take(3).forEach { label ->
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { onPresetSelected(label, buildPresetLevels(label, equalizer)) }
                        ) {
                            Text(text = label, color = textWarm)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.drop(3).forEach { label ->
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { onPresetSelected(label, buildPresetLevels(label, equalizer)) }
                        ) {
                            Text(text = label, color = textWarm)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Bass Boost",
                    style = MaterialTheme.typography.titleSmall,
                    color = textWarm
                )
                Switch(
                    checked = bassBoostEnabled,
                    onCheckedChange = {
                        onBassBoostEnabled(it)
                        bassBoost?.enabled = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accent,
                        checkedTrackColor = accent.copy(alpha = 0.5f),
                        uncheckedThumbColor = textMuted,
                        uncheckedTrackColor = textMuted.copy(alpha = 0.4f)
                    )
                )
            }
            Slider(
                value = bassBoostStrength.toFloat(),
                valueRange = 0f..1000f,
                onValueChange = { newValue ->
                    val value = newValue.toInt()
                    onBassBoostStrength(value)
                    bassBoost?.setStrength(value.toShort())
                },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = textMuted
                )
            )

            val bandCount = equalizer.numberOfBands.toInt()
            val range = equalizer.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()
            if (eqBandCount.value != bandCount || eqBandLevels.size != bandCount) {
                eqBandLevels.clear()
                repeat(bandCount) { bandIndex ->
                    val band = bandIndex.toShort()
                    eqBandLevels.add(equalizer.getBandLevel(band).toInt())
                }
                eqBandCount.value = bandCount
            } else {
                for (bandIndex in 0 until bandCount) {
                    val band = bandIndex.toShort()
                    equalizer.setBandLevel(band, eqBandLevels[bandIndex].toShort())
                }
            }

            repeat(bandCount) { bandIndex ->
                val band = bandIndex.toShort()
                val centerHz = eqBandHz.getOrNull(bandIndex) ?: (equalizer.getCenterFreq(band) / 1000).toInt()
                val level = eqBandLevels[bandIndex]

                Text(
                    text = "${centerHz} Hz",
                    style = MaterialTheme.typography.labelMedium,
                    color = textWarm,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Slider(
                    value = level.toFloat(),
                    valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                    onValueChange = { newValue ->
                        val newLevel = newValue.toInt()
                        eqBandLevels[bandIndex] = newLevel
                        equalizer.setBandLevel(band, newLevel.toShort())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = textMuted
                    )
                )
            }
        }
    }

    private fun formatHms(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d:%02d", hours, minutes, seconds)
    }
}
