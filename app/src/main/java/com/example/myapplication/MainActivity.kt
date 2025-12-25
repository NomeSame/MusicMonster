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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem

/**
 * Main activity that displays a list of audio files from the device and provides playback controls.
 */
class MainActivity : ComponentActivity() {

    // ✅ Make songs observable by Compose
    private var songs by mutableStateOf<List<Song>>(emptyList())

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

        setContent { MainScreen() }
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

        if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        } else {
            if (songs.isEmpty()) loadSongs()
        }
    }

    private fun loadSongs() {
        val context = this
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA
        )

        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        val list = mutableListOf<Song>()

        cursor?.use {
            while (it.moveToNext()) {
                val title =
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: "Unknown"
                val dataPath = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                if (dataPath != null) {
                    list.add(Song(title, Uri.fromFile(java.io.File(dataPath))))
                }
            }
        }

        songs = list
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current

        val player = remember { ExoPlayer.Builder(context).build() }
        val session = remember {
            MediaSessionCompat(context, "MusicBox").apply { isActive = true }
        }

        var currentIndex by remember { mutableStateOf(-1) }
        var isPlaying by remember { mutableStateOf(false) }
        var isShuffled by remember { mutableStateOf(false) }

        // Update player when song changes
        LaunchedEffect(currentIndex) {
            if (currentIndex in songs.indices) {
                val mediaItem = MediaItem.fromUri(songs[currentIndex].uri)
                player.setMediaItem(mediaItem)
                player.prepare()

                // ✅ Use compat metadata (matches MediaSessionCompat)
                val metadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, songs[currentIndex].title)
                    .build()
                session.setMetadata(metadata)

                if (isPlaying) player.play()
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {

                Text(
                    text = if (currentIndex in songs.indices)
                        "Now Playing: ${songs[currentIndex].title}"
                    else
                        "No song selected",
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
                                    isPlaying = true
                                    player.play()
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
                        if (isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            player.play()
                            isPlaying = true
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

        // Release resources when composable leaves composition
        DisposableEffect(Unit) {
            onDispose {
                player.release()
                session.release()
            }
        }
    }
}
