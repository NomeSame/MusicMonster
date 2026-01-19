package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import android.content.ContentUris


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
    private val isPlaying = mutableStateOf(false)
    private var controllerReady by mutableStateOf(false)


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadSongs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAudioPermissionAndLoad()

        // Start the foreground music service so lockscreen controls work.
        val intent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        setContent {
            if (controllerReady) {
                MainScreen()
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
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
            if (songs.isEmpty()) loadSongs()
        }
    }

    private fun loadSongs() {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE
        )

        val list = mutableListOf<Song>()

        contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            while (c.moveToNext()) {
                val idLong = c.getLong(idCol)
                val title = c.getString(titleCol) ?: "Unknown"

                val contentUri = ContentUris.withAppendedId(collection, idLong)

                list.add(
                    Song(
                        id = idLong.toString(),
                        title = title,
                        uri = contentUri
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
                        }

                        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                            nowPlayingTitle.value =
                                metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                        }
                    })

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
        val context = LocalContext.current


        var currentIndex by remember { mutableStateOf(-1) }
        var isPlaying by remember { mutableStateOf(false) }
        var isShuffled by remember { mutableStateOf(false) }



        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {

                Text(
                    text = nowPlayingTitle.value?.let { "Now Playing: $it" } ?: "No song selected",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(8.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(songs) { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .clickable {
                                    currentIndex = index
                                    mediaController.transportControls.playFromMediaId(
                                        songs[index].id,
                                        null
                                    )
                                }
                        ) {
                            Text(text = song.title, modifier = Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = {
                        if (isShuffled) {
                            if (songs.isNotEmpty()) {
                                currentIndex = (0 until songs.size).random()
                                isPlaying = true
                            }
                        } else {
                            if (currentIndex > 0) {
                                currentIndex--
                                isPlaying = true
                            }
                        }
                    }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev")
                    }

                    Button(onClick = {
                        if (this@MainActivity.isPlaying.value) {
                            mediaController.transportControls.pause()
                        } else {
                            mediaController.transportControls.play()
                        }

                    }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause"
                        )
                    }

                    Button(onClick = {
                        if (isShuffled) {
                            if (songs.isNotEmpty()) {
                                currentIndex = (0 until songs.size).random()
                                isPlaying = true
                            }
                        } else {
                            if (currentIndex < songs.size - 1) {
                                currentIndex++
                                isPlaying = true
                            }
                        }
                    }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next")
                    }

                    Button(onClick = { isShuffled = !isShuffled }) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            tint = if (isShuffled) Color.Green else Color.Unspecified,
                            contentDescription = "Shuffle"
                        )
                    }
                }
            }
        }
    }
}
