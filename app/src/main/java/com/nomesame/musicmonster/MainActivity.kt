package com.nomesame.musicmonster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.nomesame.musicmonster.ui.screens.PlayerScreen
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme


/**
 * Main activity that displays a list of audio files from the device and provides playback controls.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Give the previous folder's grant back before taking a new one:
            // persisted URI permissions are capped per app (128 below API 30),
            // and re-picking a folder otherwise leaks one grant every time.
            viewModel.libraryTreeUri()?.takeIf { it != uri }?.let(::releaseReadPermission)
            takeFolderPermission(uri)
            viewModel.saveLibraryTreeUri(uri)
            viewModel.loadSongs()
            viewModel.startMusicService()
        }
    }

    /**
     * Persisting the grant is best-effort: some document providers (OEM file
     * managers, cloud providers) hand out a URI they refuse to persist and
     * throw SecurityException here. The folder still works for this session, so
     * a failed persist must not take the app down.
     */
    private fun takeReadPermission(uri: Uri) {
        // Read-only grants (background image). Persisting is best-effort: some
        // document providers (OEM file managers, cloud providers) hand out a URI
        // they refuse to persist and throw SecurityException here. The resource
        // still works for this session, so a failed persist must not take the
        // app down.
        persistPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * The SAF library folder is also the write target for imported results, so
     * a folder grant is persisted as READ|WRITE when the provider offered write
     * access. The picker callback only hands back a [Uri] — the offered flags
     * are not part of the result — so write access is probed by attempting the
     * combined persist and falling back to read-only on SecurityException.
     * Providers that grant only read therefore keep the previous behavior.
     */
    private fun takeFolderPermission(uri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (!persistPermission(uri, read or write)) persistPermission(uri, read)
    }

    // Only read/write modes are accepted here. PERSISTABLE describes the
    // offered picker grant; passing it to this method is rejected by Android.
    private fun persistPermission(uri: Uri, mode: Int): Boolean =
        runCatching { contentResolver.takePersistableUriPermission(uri, mode) }.isSuccess

    private fun releaseReadPermission(uri: Uri) {
        // Release both modes so an old write grant cannot linger; fall back to
        // read-only for grants that never included write.
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (!releasePermission(uri, read or write)) releasePermission(uri, read)
    }

    private fun releasePermission(uri: Uri, mode: Int): Boolean =
        runCatching { contentResolver.releasePersistableUriPermission(uri, mode) }.isSuccess

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

    private val pickBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Persist read access so the background survives restarts.
            viewModel.customBgUri.value?.takeIf { it != uri }?.let(::releaseReadPermission)
            takeReadPermission(uri)
            viewModel.onCustomBackgroundPicked(uri)
        }
    }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadLibraryIfPossible()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the system shows our branded splash
        // theme (dark, matching the app) instead of a white flash.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        requestAudioPermissionIfNeeded()
        loadLibraryIfPossible()
        requestNotificationPermissionIfNeeded()
        viewModel.loadPlaylists()

        setContent {
            val accent by viewModel.accentColor.collectAsState()
            val language by viewModel.appLanguage.collectAsState()
            AppLanguageContent(language) {
                MyApplicationTheme(accent = accent) {
                    val controllerReady by viewModel.playbackConnection.isReady.collectAsState()
                    val playbackUnavailable by viewModel.playbackUnavailable.collectAsState()
                    if (controllerReady || playbackUnavailable) {
                        PlayerScreen(
                            viewModel = viewModel,
                            onPickFolder = { selectFolderLauncher.launch(null) },
                            onExport = { exportPlaylistsLauncher.launch("musicbox_playlists.json") },
                            onImport = { importPlaylistsLauncher.launch(arrayOf("application/json")) },
                            onAccentChange = { viewModel.setAccentColor(it) },
                            onPickBackground = { pickBackgroundLauncher.launch(arrayOf("image/*")) }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
        // Initialize the MediaController once the service has created its session.
        viewModel.connectPlayback()
    }

    override fun onStart() {
        super.onStart()
        // Re-negotiate the plugin capability on every start: the answer is never persisted, so a
        // removed plugin, a HELLO-only build or an incompatible version hides the import entry.
        viewModel.refreshImportCapability()
    }

    override fun onStop() {
        // Release the probe binding; the player and any running import job stay untouched.
        viewModel.closeImportProbe()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Load only — never re-ask. Asking here fired the permission request on
        // every single resume; from the second denial on, API 30+ auto-denies
        // without showing anything, so the user was stuck with an empty library
        // and no way to see why.
        loadLibraryIfPossible()
    }

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermissionIfNeeded() {
        // A user who already picked a SAF folder reads their music through that
        // grant; demanding the MediaStore permission on top of it is pointless.
        if (viewModel.libraryTreeUri() != null || hasAudioPermission()) return
        requestPermissionLauncher.launch(audioPermission)
    }

    private fun loadLibraryIfPossible() {
        // SAF folder counts as access on its own — without this check, denying
        // the audio permission left folder-based libraries permanently empty
        // even though nothing about them needs that permission.
        if (viewModel.libraryTreeUri() == null && !hasAudioPermission()) return
        if (viewModel.songs.value.isEmpty()) {
            viewModel.loadSongs()
            // Only start/reload service on first load — not on rotation
            viewModel.startMusicService()
        }
    }

}
