package com.example.myapplication

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication.audio.EqualizerController
import com.example.myapplication.data.PlaylistRepository
import com.example.myapplication.data.SongRepository
import com.example.myapplication.model.Playlist
import com.example.myapplication.playback.PlaybackConnection
import com.example.myapplication.ui.theme.DefaultAccent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds UI state and orchestrates the repositories, audio controller and
 * playback connection. Logic was moved verbatim from MainActivity; behavior is
 * unchanged. Owning these in the ViewModel keeps them alive across config
 * changes and keeps the Activity thin.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val prefs = application.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

    private val playlistRepository = PlaylistRepository(prefs, application.contentResolver)
    private val songRepository = SongRepository(application, prefs)

    val equalizerController = EqualizerController()
    val playbackConnection = PlaybackConnection(application)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    val playlists = mutableStateListOf<Playlist>()
    private var playlistSequence = 0

    private var serviceStarted = false
    private var connected = false

    // User-selectable accent color, persisted as an ARGB int in prefs.
    private val _accentColor = MutableStateFlow(
        Color(prefs.getInt("accent_color", DefaultAccent.toArgb()))
    )
    val accentColor: StateFlow<Color> = _accentColor.asStateFlow()

    fun setAccentColor(color: Color) {
        prefs.edit().putInt("accent_color", color.toArgb()).apply()
        _accentColor.value = color
        // Re-tint the media notification / lock screen if the service is running.
        if (serviceStarted) {
            app.startService(
                Intent(app, MusicService::class.java)
                    .setAction(MusicService.ACTION_REFRESH_NOTIFICATION)
            )
        }
    }

    /** Wires the equalizer to audio-session changes and starts the controller. */
    fun connectPlayback() {
        if (connected) return
        connected = true
        playbackConnection.onAudioSession = { id ->
            equalizerController.audioSessionId.value = id
            if (id != 0) {
                equalizerController.setupForSession(id)
            }
        }
        playbackConnection.connect { MusicService.sessionToken }
    }

    fun loadSongs() {
        _songs.value = songRepository.load()
    }

    fun loadPlaylists() {
        val loaded = playlistRepository.load() ?: return
        playlists.clear()
        playlists.addAll(loaded.playlists)
        playlistSequence = loaded.nextSequence
    }

    fun savePlaylists() {
        playlistRepository.save(playlists)
    }

    fun exportPlaylists(uri: Uri) {
        playlistRepository.export(uri, playlists)
    }

    fun importPlaylists(uri: Uri) {
        val newSequence = playlistRepository.importInto(uri, playlists, playlistSequence) ?: return
        playlistSequence = newSequence
        savePlaylists()
    }

    fun saveLibraryTreeUri(uri: Uri) {
        prefs.edit().putString("library_tree_uri", uri.toString()).apply()
    }

    fun nextUpSong(songs: List<Song>, currentId: String?): Song? {
        if (songs.isEmpty()) return null
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % songs.size
        } else {
            0
        }
        return songs.getOrNull(nextIndex)
    }

    fun createPlaylist(name: String, initialSong: Song?): Playlist {
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

    fun addSongToPlaylist(playlist: Playlist, song: Song) {
        if (!playlist.songIds.contains(song.id)) {
            playlist.songIds.add(song.id)
            savePlaylists()
        }
    }

    fun startMusicService() {
        val intent = Intent(app, MusicService::class.java)
        if (!serviceStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            serviceStarted = true
        } else {
            intent.action = MusicService.ACTION_RELOAD_LIBRARY
            app.startService(intent)
        }
    }

    fun playPlaylist(playlist: Playlist, startId: String) {
        val intent = Intent(app, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_PLAYLIST
            putStringArrayListExtra(
                MusicService.EXTRA_PLAYLIST_IDS,
                ArrayList(playlist.songIds)
            )
            putExtra(MusicService.EXTRA_PLAYLIST_START_ID, startId)
        }
        app.startService(intent)
    }

    fun startSleepTimer(durationMs: Long, fadeMs: Long) {
        if (durationMs <= 0L) return
        val intent = Intent(app, MusicService::class.java).apply {
            action = MusicService.ACTION_SET_SLEEP_TIMER
            putExtra(MusicService.EXTRA_SLEEP_MS, durationMs)
            putExtra(MusicService.EXTRA_FADE_MS, fadeMs)
        }
        app.startService(intent)
    }

    fun cancelSleepTimer() {
        val intent = Intent(app, MusicService::class.java).apply {
            action = MusicService.ACTION_CANCEL_SLEEP_TIMER
        }
        app.startService(intent)
    }
}
