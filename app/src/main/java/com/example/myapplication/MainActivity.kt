package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.app.ActivityCompat
import android.media.audiofx.Equalizer
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
                            if (id != 0) audioSessionId.value = id
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
                                                accent = iconGlow
                                            )
                                            1 -> VisualizerPanel(
                                                audioSessionId = audioSessionId.value,
                                                textWarm = textWarm,
                                                accent = iconGlow
                                            )
                                            else -> Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Screen ${page + 1}",
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    color = textWarm
                                                )
                                            }
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
        accent: Color
    ) {
        var enabled by rememberSaveable(audioSessionId) { mutableStateOf(true) }
        val equalizer = remember(audioSessionId) {
            if (audioSessionId != 0) {
                try {
                    Equalizer(0, audioSessionId).apply { this.enabled = true }
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }

        DisposableEffect(equalizer) {
            onDispose { equalizer?.release() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
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
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
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

            val bandCount = equalizer.numberOfBands.toInt()
            val range = equalizer.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()

            repeat(bandCount) { bandIndex ->
                val band = bandIndex.toShort()
                val centerHz = equalizer.getCenterFreq(band) / 1000
                var level by remember(audioSessionId, bandIndex) {
                    mutableStateOf(equalizer.getBandLevel(band).toInt())
                }

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
                        level = newValue.toInt()
                        equalizer.setBandLevel(band, newValue.toInt().toShort())
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
}
