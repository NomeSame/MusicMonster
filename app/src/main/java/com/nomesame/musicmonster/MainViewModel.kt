package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nomesame.musicmonster.audio.EqualizerController
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.PlaylistRepository
import com.nomesame.musicmonster.data.SongRepository
import com.nomesame.musicmonster.model.Playlist
import com.nomesame.musicmonster.playback.PlaybackConnection
import com.nomesame.musicmonster.ui.theme.DefaultAccent
import com.nomesame.musicmonster.ui.theme.PaletteEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val backgroundRepository = BackgroundRepository(prefs)
    private val paletteEngine = PaletteEngine(application)

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

    // --- Player card opacity ---------------------------------------------------

    private val _playerOpacity = MutableStateFlow(prefs.getFloat(KEY_PLAYER_OPACITY, 1f).coerceIn(0f, 1f))
    val playerOpacity: StateFlow<Float> = _playerOpacity.asStateFlow()

    fun setPlayerOpacity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_PLAYER_OPACITY, clamped).apply()
        _playerOpacity.value = clamped
    }

    // --- Custom background (personalization) ----------------------------------

    private val _customBgEnabled = MutableStateFlow(backgroundRepository.isEnabled())
    val customBgEnabled: StateFlow<Boolean> = _customBgEnabled.asStateFlow()

    private val _customBgUri = MutableStateFlow<Uri?>(backgroundRepository.uri())
    val customBgUri: StateFlow<Uri?> = _customBgUri.asStateFlow()

    private val _customBgScrim = MutableStateFlow(backgroundRepository.scrim())
    val customBgScrim: StateFlow<Float> = _customBgScrim.asStateFlow()

    // When a picked image yields a matching accent, this holds it so the UI can
    // ask "load a matching theme?". Null = no pending prompt.
    private val _pendingPaletteAccent = MutableStateFlow<Color?>(null)
    val pendingPaletteAccent: StateFlow<Color?> = _pendingPaletteAccent.asStateFlow()

    fun setCustomBgEnabled(enabled: Boolean) {
        backgroundRepository.setEnabled(enabled)
        _customBgEnabled.value = enabled
    }

    fun setCustomBgScrim(value: Float) {
        backgroundRepository.setScrim(value)
        _customBgScrim.value = value.coerceIn(0f, 1f)
    }

    /**
     * Called after the user picked a background image (URI already granted a
     * persistable read permission by the Activity). Persists + enables it, then
     * extracts a matching accent off-thread and, if found, raises the prompt.
     */
    fun onCustomBackgroundPicked(uri: Uri) {
        backgroundRepository.setUri(uri)
        _customBgUri.value = uri
        setCustomBgEnabled(true)
        viewModelScope.launch {
            _pendingPaletteAccent.value = paletteEngine.accentFrom(uri)
        }
    }

    /** Drops the user's picked image and falls back to the bundled default. */
    fun resetCustomBackground() {
        backgroundRepository.setUri(null)
        _customBgUri.value = null
        _pendingPaletteAccent.value = null
    }

    /** User accepted the matching-theme prompt. */
    fun applyPendingPalette() {
        _pendingPaletteAccent.value?.let { setAccentColor(it) }
        _pendingPaletteAccent.value = null
    }

    /** User declined the matching-theme prompt. */
    fun dismissPendingPalette() {
        _pendingPaletteAccent.value = null
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

    companion object {
        private const val KEY_PLAYER_OPACITY = "player_opacity"
    }
}
