package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.example.myapplication.data.PlaylistRepository
import com.example.myapplication.model.Playlist
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.activity.compose.BackHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter


/**
 * Main activity that displays a list of audio files from the device and provides playback controls.
 */
class MainActivity : ComponentActivity() {

    // ✅ Make songs observable by Compose
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private val playlists = mutableStateListOf<Playlist>()
    private var playlistSequence = 0

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

    private val prefs by lazy { getSharedPreferences("music_prefs", MODE_PRIVATE) }
    private val playlistRepository by lazy { PlaylistRepository(prefs, contentResolver) }

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString("library_tree_uri", uri.toString()).apply()
            loadSongs()
            startMusicService()
        }
    }

    private val exportPlaylistsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportPlaylistsToUri(uri)
        }
    }

    private val importPlaylistsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importPlaylistsFromUri(uri)
        }
    }


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
        loadPlaylists()

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
        val treeUri = prefs.getString("library_tree_uri", null)?.let { Uri.parse(it) }
        if (treeUri != null) {
            songs = loadSongsFromTree(treeUri)
            return
        }
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

    private fun loadSongsFromTree(treeUri: Uri): List<Song> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        val list = mutableListOf<Song>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val doc = stack.removeFirst()
            if (doc.isDirectory) {
                doc.listFiles().forEach { stack.add(it) }
            } else {
                val name = doc.name ?: "Unknown"
                val type = doc.type
                if (type?.startsWith("audio/") == true || name.endsWith(".mp3", true)
                    || name.endsWith(".m4a", true) || name.endsWith(".flac", true)
                    || name.endsWith(".wav", true) || name.endsWith(".ogg", true)
                ) {
                    list.add(
                        Song(
                            id = doc.uri.toString(),
                            title = name.substringBeforeLast('.'),
                            uri = doc.uri,
                            durationMs = 0L
                        )
                    )
                }
            }
        }
        return list.sortedBy { it.title.lowercase() }
    }

    private fun loadPlaylists() {
        val loaded = playlistRepository.load() ?: return
        playlists.clear()
        playlists.addAll(loaded.playlists)
        playlistSequence = loaded.nextSequence
    }

    private fun savePlaylists() {
        playlistRepository.save(playlists)
    }

    private fun exportPlaylistsToUri(uri: Uri) {
        playlistRepository.export(uri, playlists)
    }

    private fun importPlaylistsFromUri(uri: Uri) {
        val newSequence = playlistRepository.importInto(uri, playlists, playlistSequence) ?: return
        playlistSequence = newSequence
        savePlaylists()
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
        var playlistTargetSong by remember { mutableStateOf<Song?>(null) }
        var newPlaylistName by rememberSaveable { mutableStateOf("") }
        val showPlaylistDialog = playlistTargetSong != null

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
                    ,
                        modifier = Modifier
                            .clickable {
                                selectFolderLauncher.launch(null)
                            }
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
                                        .combinedClickable(
                                            onClick = {
                                                mediaController.transportControls.playFromMediaId(
                                                    song.id,
                                                    null
                                                )
                                            },
                                            onLongClick = {
                                                playlistTargetSong = song
                                            }
                                        ),
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
                                                playlists = playlists,
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

                            val nextUpSong = remember(songs, currentId) {
                                nextUpSong(songs, currentId)
                            }
                            if (nextUpSong != null) {
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
                                        text = nextUpSong.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textWarm,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
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
                                                    addSongToPlaylist(playlist, song)
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
                                    createPlaylist(name, playlistTargetSong)
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

    private fun playPlaylist(playlist: Playlist, startId: String) {
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_PLAYLIST
            putStringArrayListExtra(
                MusicService.EXTRA_PLAYLIST_IDS,
                ArrayList(playlist.songIds)
            )
            putExtra(MusicService.EXTRA_PLAYLIST_START_ID, startId)
        }
        startService(intent)
    }

    @Composable
    private fun QueueAndSleepPanel(
        songs: List<Song>,
        playlists: SnapshotStateList<Playlist>,
        textWarm: Color,
        textMuted: Color,
        accent: Color
    ) {
        var hoursText by rememberSaveable { mutableStateOf("") }
        var minutesText by rememberSaveable { mutableStateOf("") }
        var secondsText by rememberSaveable { mutableStateOf("") }
        var sleepTotalMs by rememberSaveable { mutableStateOf(0L) }
        var sleepRemainingMs by rememberSaveable { mutableStateOf(0L) }
        var sleepTargetElapsedMs by rememberSaveable { mutableStateOf<Long?>(null) }
        var timerRunning by rememberSaveable { mutableStateOf(false) }
        var activePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
        var showAddSongsDialog by rememberSaveable { mutableStateOf(false) }
        var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
        var createPlaylistName by rememberSaveable { mutableStateOf("") }
        val activePlaylist = playlists.firstOrNull { it.id == activePlaylistId }

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

        BackHandler(activePlaylist != null) {
            activePlaylistId = null
            showAddSongsDialog = false
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                Column {
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = textMuted.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            if (activePlaylist != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { activePlaylistId = null }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = textWarm
                            )
                        }
                        Text(
                            text = activePlaylist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = textWarm
                        )
                    }

                    Button(
                        onClick = { showAddSongsDialog = true },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("Add songs", color = textWarm)
                    }
                }

                val playlistSongs = activePlaylist.songIds.mapNotNull { id ->
                    songs.firstOrNull { it.id == id }
                }

                if (playlistSongs.isEmpty()) {
                    item {
                        Text(
                            text = "No songs in this playlist",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted
                        )
                    }
                } else {
                    itemsIndexed(playlistSongs) { _, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    playPlaylist(activePlaylist, song.id)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textWarm,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = {
                                activePlaylist.songIds.remove(song.id)
                                savePlaylists()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = textMuted
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }

            } else {
                item {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.titleMedium,
                        color = textWarm,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { exportPlaylistsLauncher.launch("musicbox_playlists.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export", color = textWarm)
                        }
                        Button(
                            onClick = { importPlaylistsLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import", color = textWarm)
                        }
                    }
                    Button(
                        onClick = { showCreatePlaylistDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text("New playlist", color = textWarm)
                    }
                }

                if (playlists.isEmpty()) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PlaylistAdd,
                                contentDescription = null,
                                tint = textMuted,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Long-press a song to create a playlist",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textMuted
                            )
                        }
                    }
                } else {
                    itemsIndexed(playlists) { _, playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable { activePlaylistId = playlist.id },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textWarm,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${playlist.songIds.size} songs",
                                style = MaterialTheme.typography.labelSmall,
                                color = textMuted,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            IconButton(onClick = {
                                playlists.remove(playlist)
                                savePlaylists()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete playlist",
                                    tint = textMuted
                                )
                            }
                        }
                        Divider(color = textMuted.copy(alpha = 0.3f))
                    }
                }

            }
        }

        if (showAddSongsDialog && activePlaylist != null) {
            val availableSongs = songs.filterNot { activePlaylist.songIds.contains(it.id) }
            AlertDialog(
                onDismissRequest = { showAddSongsDialog = false },
                title = {
                    Text(
                        text = "Add to ${activePlaylist.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = textWarm
                    )
                },
                text = {
                    if (availableSongs.isEmpty()) {
                        Text(
                            text = "All songs already in playlist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 280.dp)
                        ) {
                            itemsIndexed(availableSongs) { _, song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clickable {
                                            addSongToPlaylist(activePlaylist, song)
                                            showAddSongsDialog = false
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textWarm,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showAddSongsDialog = false }) {
                        Text("Close", color = textWarm)
                    }
                }
            )
        }

        if (showCreatePlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showCreatePlaylistDialog = false },
                title = {
                    Text(
                        text = "Create Playlist",
                        style = MaterialTheme.typography.titleMedium,
                        color = textWarm
                    )
                },
                text = {
                    OutlinedTextField(
                        value = createPlaylistName,
                        onValueChange = { createPlaylistName = it.take(24) },
                        label = { Text("Name", color = textMuted) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            focusedLabelColor = accent,
                            unfocusedBorderColor = textMuted,
                            unfocusedLabelColor = textMuted,
                            cursorColor = accent
                        ),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val name = createPlaylistName.trim()
                        if (name.isNotEmpty()) {
                            val created = createPlaylist(name, null)
                            activePlaylistId = created.id
                            showAddSongsDialog = true
                        }
                        createPlaylistName = ""
                        showCreatePlaylistDialog = false
                    }) {
                        Text("Create", color = textWarm)
                    }
                },
                dismissButton = {
                    Button(onClick = {
                        createPlaylistName = ""
                        showCreatePlaylistDialog = false
                    }) {
                        Text("Cancel", color = textWarm)
                    }
                }
            )
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

    private fun nextUpSong(songs: List<Song>, currentId: String?): Song? {
        if (songs.isEmpty()) return null
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % songs.size
        } else {
            0
        }
        return songs.getOrNull(nextIndex)
    }

    private fun createPlaylist(name: String, initialSong: Song?): Playlist {
        val playlist = Playlist(
            id = "playlist_${playlistSequence++}",
            name = name.trim(),
            songIds = mutableStateListOf()
        )
        if (initialSong != null) {
            playlist.songIds.add(initialSong.id)
        }
        playlists.add(playlist)
        savePlaylists()
        return playlist
    }

    private fun addSongToPlaylist(playlist: Playlist, song: Song) {
        if (!playlist.songIds.contains(song.id)) {
            playlist.songIds.add(song.id)
            savePlaylists()
        }
    }
}
