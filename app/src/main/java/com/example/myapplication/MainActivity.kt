package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.screens.PlayerScreen
import com.example.myapplication.ui.theme.MyApplicationTheme


/**
 * Main activity that displays a list of audio files from the device and provides playback controls.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.saveLibraryTreeUri(uri)
            viewModel.loadSongs()
            viewModel.startMusicService()
        }
    }

    private val exportPlaylistsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportPlaylists(uri)
        }
    }

    private val importPlaylistsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importPlaylists(uri)
        }
    }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.loadSongs()
            viewModel.startMusicService()
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
        viewModel.loadPlaylists()

        setContent {
            val accent by viewModel.accentColor.collectAsState()
            MyApplicationTheme(accent = accent) {
                val controllerReady by viewModel.playbackConnection.isReady.collectAsState()
                if (controllerReady) {
                    PlayerScreen(
                        viewModel = viewModel,
                        onPickFolder = { selectFolderLauncher.launch(null) },
                        onExport = { exportPlaylistsLauncher.launch("musicbox_playlists.json") },
                        onImport = { importPlaylistsLauncher.launch(arrayOf("application/json")) }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        // Initialize the MediaController once the service has created its session.
        viewModel.connectPlayback()
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
            if (viewModel.songs.value.isEmpty()) {
                viewModel.loadSongs()
            }
            viewModel.startMusicService()
        }
    }

}
