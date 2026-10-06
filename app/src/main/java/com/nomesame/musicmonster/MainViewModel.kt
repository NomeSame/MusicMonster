package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.nomesame.musicmonster.audio.EqualizerController
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.PlaylistRepository
import com.nomesame.musicmonster.data.SongRepository
import com.nomesame.musicmonster.data.floatOr
import com.nomesame.musicmonster.data.intOr
import com.nomesame.musicmonster.data.stringOr
import com.nomesame.musicmonster.data.unitFloat
import com.nomesame.musicmonster.data.PlaylistCodec
import com.nomesame.musicmonster.model.Playlist
import com.nomesame.musicmonster.playback.PlaybackConnection
import com.nomesame.musicmonster.ui.theme.DefaultAccent
import com.nomesame.musicmonster.ui.theme.PaletteEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds UI state and orchestrates the repositories, audio controller and
 * playback connection. Logic was moved verbatim from MainActivity; behavior is
 * unchanged. Owning these in the ViewModel keeps them alive across config
 * changes and keeps the Activity thin.
 */
@OptIn(UnstableApi::class)
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

    // True when the MediaSession never became available (e.g. audio
    // permission denied); UI uses this to stop the loading spinner.
    private val _playbackUnavailable = MutableStateFlow(false)
    val playbackUnavailable: StateFlow<Boolean> = _playbackUnavailable.asStateFlow()

    // User-selectable accent color, persisted as an ARGB int in prefs.
    private val _accentColor = MutableStateFlow(
        Color(prefs.intOr("accent_color", DefaultAccent.toArgb()))
    )
    val accentColor: StateFlow<Color> = _accentColor.asStateFlow()

    fun setAccentColor(color: Color) {
        prefs.edit().putInt("accent_color", color.toArgb()).apply()
        _accentColor.value = color
        // Re-tint the media notification / lock screen if the service is running.
        // Must go through startPlaybackService: a plain startService() here
        // throws on API 26+ whenever the service has since been killed, and
        // IllegalStateException/ForegroundServiceStartNotAllowedException on
        // API 31+ when the app isn't in the foreground.
        if (serviceStarted) {
            startPlaybackService(
                Intent(app, MusicService::class.java)
                    .setAction(MusicService.ACTION_REFRESH_NOTIFICATION)
            )
        }
    }

    // --- Player card opacity ---------------------------------------------------

    private val _playerOpacity = MutableStateFlow(prefs.floatOr(KEY_PLAYER_OPACITY, 1f).coerceIn(0f, 1f))
    val playerOpacity: StateFlow<Float> = _playerOpacity.asStateFlow()

    fun setPlayerOpacity(value: Float) {
        val clamped = unitFloat(value, 1f)
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
        _customBgScrim.value = backgroundRepository.scrim()
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
            } else {
                // ExoPlayer released its audio session (id -> 0). setupForSession(0)
                // releases the Equalizer/BassBoost and nulls them; without this the
                // effects would leak on the orphaned session.
                equalizerController.setupForSession(0)
            }
        }
        playbackConnection.connect(
            tokenProvider = { MusicService.sessionToken },
            onUnavailable = {
                // Session never became available (e.g. audio permission
                // denied): mark the connection ready so the UI does not spin
                // forever. The user can still pick a folder (SAF) to play.
                _playbackUnavailable.value = true
            }
        )
    }

    /**
     * Loads the library off the main thread. A MediaStore query or a recursive
     * SAF walk is unbounded work: on a large library or slow storage doing it
     * inline froze the UI (and, past 5s, produced an ANR) — a failure mode that
     * only ever showed up on other people's devices.
     */
    fun loadSongs() {
        viewModelScope.launch {
            _songs.value = withContext(Dispatchers.IO) { songRepository.load() }
        }
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
        // File I/O through a SAF provider can block for seconds (cloud-backed
        // providers especially); keep it off the UI thread.
        val snapshot = playlists.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { playlistRepository.export(uri, snapshot) }
        }
    }

    fun importPlaylists(uri: Uri) {
        viewModelScope.launch {
            // Read on IO, merge on main: importInto mutates the SnapshotStateList
            // the UI observes, and snapshot state must be written from one thread.
            val raw = withContext(Dispatchers.IO) { playlistRepository.readText(uri) } ?: return@launch
            val newSequence = playlistRepository.importInto(raw, playlists, playlistSequence)
                ?: return@launch
            playlistSequence = newSequence
            savePlaylists()
        }
    }

    fun saveLibraryTreeUri(uri: Uri) {
        prefs.edit().putString("library_tree_uri", uri.toString()).apply()
    }

    /** The persisted SAF library folder, or null when the library is MediaStore. */
    fun libraryTreeUri(): Uri? = runCatching {
        prefs.stringOr("library_tree_uri", null)?.let(Uri::parse)
    }.getOrNull()

    fun nextUpSong(songs: List<Song>, currentId: String?): Song? = MusicLogic.nextUpSong(songs, currentId)

    fun createPlaylist(name: String, initialSong: Song?): Playlist {
        val available = PlaylistCodec.nextSequence(playlists.map { it.id }, playlistSequence)
        playlistSequence = if (available == Int.MAX_VALUE) 0 else available + 1
        val playlist = Playlist(
            id = "playlist_$available",
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

    /**
     * Starts (or sends a command to) the foreground playback service.
     *
     * On API 26+ the service is a foreground service, so any intent targeting it
     * must use startForegroundService: plain startService() from the background
     * throws ForegroundServiceStartNotAllowedException on API 34+ when the
     * service isn't already the foreground. startForegroundService is legal both
     * when the service is freshly created and when it is already running —
     * MusicService.onCreate always calls startForeground(Notification) promptly,
     * satisfying the framework's 5s deadline either way.
     */
    private fun startPlaybackService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun startMusicService() {
        val intent = Intent(app, MusicService::class.java)
        if (!serviceStarted) {
            startPlaybackService(intent)
            serviceStarted = true
        } else {
            intent.action = MusicService.ACTION_RELOAD_LIBRARY
            startPlaybackService(intent)
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
        startPlaybackService(intent)
    }

    fun startSleepTimer(durationMs: Long, fadeMs: Long) {
        if (durationMs <= 0L) return
        val intent = Intent(app, MusicService::class.java).apply {
            action = MusicService.ACTION_SET_SLEEP_TIMER
            putExtra(MusicService.EXTRA_SLEEP_MS, durationMs)
            putExtra(MusicService.EXTRA_FADE_MS, fadeMs)
        }
        startPlaybackService(intent)
    }

    fun cancelSleepTimer() {
        val intent = Intent(app, MusicService::class.java).apply {
            action = MusicService.ACTION_CANCEL_SLEEP_TIMER
        }
        startPlaybackService(intent)
    }

    /**
     * Equalizer and BassBoost are native AudioEffect handles — a limited,
     * process-wide resource on many OEM audio HALs. Nothing else releases them
     * when the app goes away, so do it here.
     */
    override fun onCleared() {
        playbackConnection.disconnect()
        equalizerController.setupForSession(0)
        super.onCleared()
    }

    companion object {
        private const val KEY_PLAYER_OPACITY = "player_opacity"
    }
}
