package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.myapplication.Song
// Removed unused import of SongList
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import android.provider.MediaStore

import androidx.compose.foundation.clickable


/**
 * Main activity that displays a list of audio files from the device and provides playback controls.
 */
class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) loadSongs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request READ_MEDIA_AUDIO permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            loadSongs()
        }
    }

    private var songs: List<Song> = emptyList()
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
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                val title = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: "Unknown"
                val dataPath = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                if (dataPath != null) {
                    list.add(Song(title, Uri.fromFile(java.io.File(dataPath))))
                }
            }
        }
        songs = list
    }

    @RequiresApi(33)
    override fun onResume() {
        super.onResume()
        // Ensure permission is still granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val player = remember { ExoPlayer.Builder(context).build() }
        var currentIndex by remember { mutableStateOf(-1) }
        var isPlaying by remember { mutableStateOf(false) }
        var isShuffled by remember { mutableStateOf(false) }

        // Update player when song changes
        LaunchedEffect(currentIndex) {
            if (currentIndex in songs.indices) {
                val mediaItem = MediaItem.fromUri(songs[currentIndex].uri)
                player.setMediaItem(mediaItem)
                player.prepare()
                if (isPlaying) player.play()
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                // Display current playing song
                Text(
                    text = if (currentIndex >= 0 && currentIndex < songs.size) "Now Playing: ${songs[currentIndex].title}" else "No song selected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(8.dp)
                )
                // Song list
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(songs) { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .clickable { currentIndex = index; isPlaying = true }
                        ) {
                            Text(text = song.title, modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = {
                        if (isShuffled) {
                            // Random previous
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
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause")
                    }
                    Button(onClick = {
                        if (isShuffled) {
                            // Random next
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
                    // Shuffle toggle button
                    Button(onClick = { isShuffled = !isShuffled }) {
                        Icon(
                            imageVector = if (isShuffled) Icons.Default.Shuffle else Icons.Default.Shuffle,
                            contentDescription = "Shuffle"
                        )
                    }
                }
            }
        }

        // Release player when composable leaves composition
        DisposableEffect(player) {
            onDispose { player.release() }
        }
    }

    override fun onStart() {
        super.onStart()
        setContent { MainScreen() }
    }
}
