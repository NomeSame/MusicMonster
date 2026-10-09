package com.nomesame.musicmonster

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.graphics.Color
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ActivityCompat
import androidx.media.session.MediaButtonReceiver

import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.concurrent.Executors
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat

import com.nomesame.musicmonster.data.booleanOr
import com.nomesame.musicmonster.data.intOr
import com.nomesame.musicmonster.data.longOr
import com.nomesame.musicmonster.data.stringOr
import android.content.SharedPreferences


@UnstableApi
class MusicService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
    private val openPlayerIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    private val positionHandler = Handler(Looper.getMainLooper())
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var fadeRunnable: Runnable? = null
    private var originalVolume = 1f
    private val positionUpdate = object : Runnable {
        override fun run() {
            setPlaybackState(player.isPlaying)
            if (player.isPlaying) {
                positionHandler.postDelayed(this, 1000L)
            }
        }
    }

    // Keep titles in parallel with playlist for notification text
    private var titles: List<String> = emptyList()
    private var libraryItems: Map<String, MediaItem> = emptyMap()
    private var libraryTitles: Map<String, String> = emptyMap()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("music_prefs", MODE_PRIVATE)
    }

    /** Single thread so two overlapping library scans can never interleave. */
    private val loadExecutor = Executors.newSingleThreadExecutor()

    /** False while the first (or a reloading) library scan is still running. */
    private var libraryReady = false

    /** Commands that arrived before the library was ready; replayed after. */
    private val pendingCommands = mutableListOf<Intent>()

    /** Set in onDestroy so results arriving late never touch a released player. */
    private var playerReleased = false

    /** A play/playFromMediaId that arrived before the library was ready. */
    private val pendingPlayback = PendingPlaybackRequest()

    companion object {
        const val CHANNEL_ID = "monsterplayer_channel"
        const val NOTIFICATION_ID = 1
        const val PREF_LAST_SONG_ID = "last_song_id"
        const val PREF_LAST_POSITION = "last_position"
        const val PREF_SHUFFLE_ENABLED = "shuffle_enabled"
        const val ACTION_TOGGLE_SHUFFLE = "com.nomesame.musicmonster.action.TOGGLE_SHUFFLE"
        const val ACTION_RELOAD_LIBRARY = "com.nomesame.musicmonster.action.RELOAD_LIBRARY"
        const val ACTION_SET_SLEEP_TIMER = "com.nomesame.musicmonster.action.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.nomesame.musicmonster.action.CANCEL_SLEEP_TIMER"
        const val ACTION_PLAY_PLAYLIST = "com.nomesame.musicmonster.action.PLAY_PLAYLIST"
        const val ACTION_REFRESH_NOTIFICATION = "com.nomesame.musicmonster.action.REFRESH_NOTIFICATION"
        const val EXTRA_SLEEP_MS = "extra_sleep_ms"
        const val EXTRA_FADE_MS = "extra_fade_ms"
        const val EXTRA_PLAYLIST_IDS = "extra_playlist_ids"
        const val EXTRA_PLAYLIST_START_ID = "extra_playlist_start_id"
        const val EXTRA_PLAYLIST_START_INDEX = "extra_playlist_start_index"
        /**
         * Holds the session token once the service has created its MediaSession.
         * Activities can read this to construct a {@link MediaControllerCompat}.
         */
        @Volatile
        var sessionToken: MediaSessionCompat.Token? = null

        /** Commands that are meaningless until the library scan has finished. */
        private val NEEDS_LIBRARY = setOf(ACTION_PLAY_PLAYLIST, ACTION_RELOAD_LIBRARY)
    }

    override fun onCreate() {
        super.onCreate()

        // Notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // LOW, not HIGH: the notification is re-posted on every track
            // change and on every position tick. On IMPORTANCE_HIGH each of
            // those becomes a heads-up popup (and a sound on several OEM
            // builds) for the whole listening session.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        player = ExoPlayer.Builder(this).build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        // Without audio focus the app talks over calls, alarms and other
        // players, and never ducks/pauses when something else takes focus.
        // handleAudioBecomingNoisy pauses instead of blasting the speaker when
        // headphones/BT are disconnected — the single most-reported "player
        // bug" there is, and it is one builder call.
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        player.setHandleAudioBecomingNoisy(true)
        // WAKE_LOCK is declared in the manifest; without setWakeMode the CPU
        // can sleep mid-track on some devices once the screen goes off.
        player.setWakeMode(C.WAKE_MODE_LOCAL)

        session = MediaSessionCompat(this, "MonsterPlayerService").apply {
            // System media cards need an explicit destination for opening our UI.
            setSessionActivity(openPlayerIntent)
            isActive = true
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == null) return
                    // The session is published before the library scan finishes
                    // (so the framework's startForeground deadline is met), so
                    // a play request can legitimately arrive first — from the
                    // lock screen or a headset button right after a cold start.
                    // Remember it instead of dropping it on the floor.
                    if (!libraryReady) {
                        pendingPlayback.play(mediaId)
                        return
                    }
                    startPlayback(mediaId)
                }

                override fun onPlay() {
                    if (!libraryReady) {
                        pendingPlayback.play()
                        return
                    }
                    startPlayback(null)
                }

                override fun onPause() {
                    pendingPlayback.pause()
                    savePlaybackState()
                    player.pause()
                    setPlaybackState(false)
                    updateNotification(false)
                }

                override fun onSkipToNext() {
                    player.seekToNextMediaItem()
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (!player.isPlaying) player.play()
                    setPlaybackState(true)
                    updateSessionMetadata()
                    updateNotification(true)
                }

                override fun onSkipToPrevious() {
                    player.seekToPreviousMediaItem()
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (!player.isPlaying) player.play()
                    setPlaybackState(true)
                    updateSessionMetadata()
                    updateNotification(true)
                }

                override fun onSetShuffleMode(shuffleMode: Int) {
                    val enabled = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
                    player.shuffleModeEnabled = enabled
                    if (enabled) {
                        reshufflePlaylist()
                    }
                    session.setShuffleMode(shuffleMode)
                    savePlaybackState()
                    setPlaybackState(player.isPlaying)
                    updateNotification(player.isPlaying)
                }

                override fun onSeekTo(pos: Long) {
                    player.seekTo(pos.coerceAtLeast(0L))
                    savePlaybackState()
                    setPlaybackState(player.isPlaying)
                    updateSessionMetadata()
                    updateNotification(player.isPlaying)
                }

                override fun onStop() {
                    pendingPlayback.pause()
                    player.stop()
                    setPlaybackState(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
        }
        sessionToken = session.sessionToken
        updateSessionExtras()
        session.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE)
        updateSessionMetadata()


        // Keep notification in sync if user changes track / state
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setPlaybackState(isPlaying)
                updateNotification(isPlaying)
                if (isPlaying) {
                    positionHandler.removeCallbacks(positionUpdate)
                    positionHandler.post(positionUpdate)
                } else {
                    positionHandler.removeCallbacks(positionUpdate)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    updateSessionMetadata()
                    updateSessionExtras()
                    setPlaybackState(player.isPlaying)
                    updateNotification(player.isPlaying)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player.shuffleModeEnabled &&
                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                ) {
                    reshufflePlaylist()
                }
                updateSessionMetadata()
                setPlaybackState(player.isPlaying)
                updateNotification(player.isPlaying)
                savePlaybackState()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                updateSessionExtras()
            }
        })

        setPlaybackState(false)
        // startForeground FIRST, library scan after. The framework kills the
        // service with ForegroundServiceDidNotStartInTimeException if this call
        // is more than ~5s after the start request, and the scan below walks a
        // whole MediaStore or SAF tree — seconds on a large library or a slow
        // SD card. Scanning first made the crash a function of library size,
        // i.e. it only ever happened on other people's devices.
        startForeground(NOTIFICATION_ID, buildNotification(false))
        loadLibraryAsync(restoreLastSession = true)
    }

    /**
     * Scans the library off the main thread and applies the result on it.
     * The scan (MediaStore query or recursive SAF walk) is unbounded work and
     * would otherwise freeze the UI thread this service shares with the app.
     *
     * Commands that need the library are queued in [pendingCommands] while a
     * scan is in flight, so a play request that arrives during startup is
     * executed afterwards instead of silently dropped.
     */
    private fun loadLibraryAsync(restoreLastSession: Boolean) {
        loadExecutor.execute {
            val loaded = runCatching { loadDevicePlaylist() }.getOrElse {
                // A misbehaving DocumentsProvider can throw anything at all.
                // An unreadable library must not take the service down.
                Triple(emptyList(), emptyList(), emptyMap())
            }
            positionHandler.post { applyLibrary(loaded, restoreLastSession) }
        }
    }

    private fun applyLibrary(
        loaded: Triple<List<MediaItem>, List<String>, Map<String, Long>>,
        restoreLastSession: Boolean,
    ) {
        if (playerReleased) return
        val (items, itemTitles, _) = loaded
        titles = itemTitles
        libraryItems = items.associateBy { it.mediaId }
        libraryTitles = items.zip(itemTitles).associate { it.first.mediaId to it.second }

        if (restoreLastSession) {
            if (items.isNotEmpty()) {
                player.setMediaItems(items)
                player.prepare()
            }
            val (lastId, lastPos, shuffleWasOn) = restorePlaybackState()
            if (shuffleWasOn && items.isNotEmpty()) {
                player.shuffleModeEnabled = true
                reshufflePlaylist()
            }
            if (lastId != null && items.isNotEmpty()) {
                val targetIndex = items.indexOfFirst { it.mediaId == lastId }
                if (targetIndex >= 0) {
                    // A stale saved position can exceed the track (file
                    // replaced/re-encoded); ExoPlayer clamps, but never seek
                    // to a negative one.
                    player.seekTo(targetIndex, lastPos.coerceAtLeast(0L))
                }
            }
        } else {
            applyReloadedLibrary(items)
        }

        libraryReady = true
        updateSessionMetadata()
        setPlaybackState(player.isPlaying)
        updateNotification(player.isPlaying)

        val queued = pendingCommands.toList()
        pendingCommands.clear()
        queued.forEach { handleCommand(it) }

        pendingPlayback.consume()?.let { startPlayback(it.mediaId) }
    }

    /**
     * Starts playback, optionally jumping to [mediaId] first. Shared by the
     * MediaSession callbacks and the replay of a request that arrived while the
     * library was still loading.
     */
    private fun startPlayback(mediaId: String?) {
        if (player.mediaItemCount == 0) return
        if (mediaId != null) {
            val targetIndex = (0 until player.mediaItemCount)
                .firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
                ?: return
            player.seekTo(targetIndex, 0L)
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        setPlaybackState(true)
        updateSessionMetadata()
        updateSessionExtras()
        updateNotification(true)
    }

    private fun updateSessionMetadata() {
        val idx = player.currentMediaItemIndex
        val currentTitle = if (idx in titles.indices) titles[idx] else getString(R.string.no_song_selected)
        val currentId = if (idx in titles.indices) player.getMediaItemAt(idx).mediaId else null
        val duration = player.duration.takeIf { it > 0L } ?: 0L

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
        )
    }

    private fun updateSessionExtras() {
        // Publish 0 as well. Suppressing it meant the "session went away"
        // signal never reached the UI, so the Equalizer/BassBoost effects
        // attached to a dead audio session were never released.
        session.setExtras(Bundle().apply {
            putInt("audio_session_id", player.audioSessionId)
        })
    }

    // Handles action intents (e.g. from notification/lock-screen PendingIntents)
    // delivered to this service, since it isn't a bound service.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY restarts deliver a null intent; nothing to dispatch.
        if (intent == null) return START_STICKY
        if (!libraryReady && NEEDS_LIBRARY.contains(intent.action)) {
            // The scan is still running. Dropping the command here is what made
            // "tap a song right after launch does nothing" reproducible only on
            // slow devices / big libraries.
            pendingCommands.add(intent)
            return START_STICKY
        }
        handleCommand(intent)
        return START_STICKY
    }

    private fun handleCommand(intent: Intent?): Int {
        if (intent?.action == ACTION_TOGGLE_SHUFFLE) {
            val newMode = if (player.shuffleModeEnabled) {
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            } else {
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            }
            player.shuffleModeEnabled = newMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
            if (player.shuffleModeEnabled) {
                reshufflePlaylist()
            }
            session.setShuffleMode(newMode)
            setPlaybackState(player.isPlaying)
            updateNotification(player.isPlaying)
            return START_STICKY
        }
        if (intent?.action == ACTION_RELOAD_LIBRARY) {
            libraryReady = false
            loadLibraryAsync(restoreLastSession = false)
            return START_STICKY
        }
        if (intent?.action == ACTION_SET_SLEEP_TIMER) {
            val sleepMs = intent.getLongExtra(EXTRA_SLEEP_MS, 0L)
            val fadeMs = intent.getLongExtra(EXTRA_FADE_MS, 0L)
            if (sleepMs > 0L) {
                startSleepTimer(sleepMs, fadeMs)
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_CANCEL_SLEEP_TIMER) {
            cancelSleepTimer()
            return START_STICKY
        }
        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            updateNotification(player.isPlaying)
            return START_STICKY
        }
        if (intent?.action == ACTION_PLAY_PLAYLIST) {
            val ids = intent.getStringArrayListExtra(EXTRA_PLAYLIST_IDS) ?: emptyList()
            val startId = intent.getStringExtra(EXTRA_PLAYLIST_START_ID)
            val startIndex = if (intent.hasExtra(EXTRA_PLAYLIST_START_INDEX))
                intent.getIntExtra(EXTRA_PLAYLIST_START_INDEX, -1) else null
            setPlaylistAndPlay(ids, startId, startIndex)
            return START_STICKY
        }

        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    // Swiping the app out of Recents does not stop a foreground service (so
    // onDestroy may not run) — persist here so state survives even while playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackState()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePlaybackState()
        session.isActive = false
        session.release()
        // Order matters: cancelSleepTimer() writes player.volume, so it has to
        // run *before* release(). The old order touched a released ExoPlayer on
        // every service teardown.
        positionHandler.removeCallbacks(positionUpdate)
        cancelSleepTimer()
        playerReleased = true
        player.release()
        loadExecutor.shutdownNow()
        // A stale token would keep the UI trying to bind a dead session.
        sessionToken = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Share the exact same provider handling, filtering and ordering as the UI.
    private fun loadDevicePlaylist(): Triple<List<MediaItem>, List<String>, Map<String, Long>> {
        val songs = com.nomesame.musicmonster.data.SongRepository(this, prefs).load()
        return Triple(
            songs.map { MediaItem.Builder().setMediaId(it.id).setUri(it.uri).build() },
            songs.map { it.title },
            songs.associate { it.id to it.durationMs },
        )
    }

    private fun setPlaybackState(isPlaying: Boolean) {
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                    PlaybackStateCompat.ACTION_STOP

        val state =
            if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    // Never publish a negative position. ExoPlayer reports one
                    // for an unset/unprepared position, and -1 is
                    // PLAYBACK_POSITION_UNKNOWN to every MediaSession consumer:
                    // the lock-screen scrubber and the in-app seek bar both
                    // jump when they see it. Surfaced on API 24 by
                    // PlaybackSessionInstrumentedTest.seekBeyondTrackEndIsClamped.
                    player.currentPosition.coerceAtLeast(0L),
                    if (isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun accentColorInt(): Int =
        getSharedPreferences("music_prefs", MODE_PRIVATE)
            .intOr("accent_color", Color.parseColor("#E58B3C"))

    private fun buildNotification(isPlaying: Boolean): Notification {
        val accentInt = accentColorInt()
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val pendingIntentPlayPause =
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
        val pendingIntentNext =
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        val pendingIntentPrev =
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        val pendingIntentShuffle =
            PendingIntent.getService(
                this,
                0,
                Intent(this, MusicService::class.java).setAction(ACTION_TOGGLE_SHUFFLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val idx = player.currentMediaItemIndex
        val currentTitle = if (idx in titles.indices) titles[idx] else getString(R.string.no_song_selected)
        val shuffleOn = player.shuffleModeEnabled
        val shuffleLabel = if (shuffleOn) getString(R.string.shuffle_on) else getString(R.string.shuffle_off)

        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val contentView = RemoteViews(packageName, R.layout.notification_music_monster).apply {
            setTextViewText(R.id.notif_title, currentTitle)
            setTextViewText(R.id.notif_time_current, MusicLogic.formatTime(position))
            setTextViewText(R.id.notif_time_duration, MusicLogic.formatTime(duration))
            setProgressBar(
                R.id.notif_progress,
                duration.toInt().coerceAtLeast(1),
                position.toInt().coerceAtLeast(0),
                false
            )
        }

        val publicNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(openPlayerIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTitle)
            .setContentText("")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setColor(accentInt)
            .setColorized(true)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), pendingIntentPrev)
            .addAction(playPauseIcon, if (isPlaying) getString(R.string.pause) else getString(R.string.play), pendingIntentPlayPause)
            .addAction(android.R.drawable.ic_media_next, getString(R.string.next), pendingIntentNext)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(openPlayerIntent)
            .setSmallIcon(R.drawable.ic_notification)
            // Use the current track title as the notification title so that
            // the lock‑screen banner displays the same name as the song
            // actually playing.  The contentText is left empty to make the
            // title appear larger on the lock screen.
            .setContentTitle(currentTitle)
            .setContentText("")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setColor(accentInt)
            .setColorized(true)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), pendingIntentPrev)
            .addAction(playPauseIcon, if (isPlaying) getString(R.string.pause) else getString(R.string.play), pendingIntentPlayPause)
            .addAction(android.R.drawable.ic_media_next, getString(R.string.next), pendingIntentNext)
            .addAction(R.drawable.ic_shuffle, shuffleLabel, pendingIntentShuffle)
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setStyle(
                androidx.media.app.NotificationCompat.DecoratedMediaCustomViewStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPublicVersion(publicNotification)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(isPlaying: Boolean) {
        if (!canPostNotifications()) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(isPlaying))
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** Swaps in a freshly scanned library, keeping the current track playing. */
    private fun applyReloadedLibrary(items: List<MediaItem>) {
        // When the first scan came up empty (permission not granted yet, folder
        // not picked yet), there is no current item to preserve — fall back to
        // the persisted one. Without this the remembered song and position were
        // lost for good on exactly the launch where the user finally granted
        // the permission.
        val restored = if (player.currentMediaItem == null) restorePlaybackState() else null
        val currentId = player.currentMediaItem?.mediaId ?: restored?.first
        val currentPosition = restored?.second ?: player.currentPosition
        if (items.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            return
        }

        val targetIndex = currentId?.let { id ->
            items.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 }
        } ?: 0

        player.setMediaItems(items, targetIndex, currentPosition.coerceAtLeast(0L))
        player.prepare()
        if (player.shuffleModeEnabled) {
            reshufflePlaylist()
        }
    }

    private fun setPlaylistAndPlay(ids: List<String>, startId: String?, originalStartIndex: Int? = null) {
        if (ids.isEmpty()) return
        // Resolve items and titles together, and take the start index from the
        // *resolved* list — see MusicLogic.resolvePlaylist for why doing this
        // per-list was a crash waiting for a deleted song.
        val (resolved, startIndex) = MusicLogic.resolvePlaylist(ids, startId, originalStartIndex) { id ->
            libraryItems[id]?.let { item -> item to (libraryTitles[id] ?: item.mediaId) }
        }
        if (resolved.isEmpty()) return
        val items = resolved.map { it.first }
        titles = resolved.map { it.second }
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
        if (player.shuffleModeEnabled) {
            reshufflePlaylist()
        }
        updateSessionMetadata()
        setPlaybackState(true)
        updateNotification(true)
    }

    private fun reshufflePlaylist() {
        val count = player.mediaItemCount
        if (count > 1) {
            val seed = buildShuffleSeed()
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(count, seed))
        }
    }

    private fun buildShuffleSeed(): Long = MusicLogic.buildShuffleSeed()

    private fun startSleepTimer(durationMs: Long, fadeMs: Long) {
        cancelSleepTimer()
        val plan = SleepTimerPlan.create(durationMs, fadeMs) ?: return
        val safeFadeMs = plan.fadeMs
        val waitMs = plan.waitMs
        sleepRunnable = Runnable {
            sleepRunnable = null
            if (safeFadeMs > 0L) {
                startFadeOut(safeFadeMs)
            } else {
                player.pause()
                setPlaybackState(false)
                updateNotification(false)
            }
        }
        sleepHandler.postDelayed(sleepRunnable!!,
            waitMs.coerceAtMost(Long.MAX_VALUE - SystemClock.uptimeMillis()))
    }

    private fun startFadeOut(fadeMs: Long) {
        fadeRunnable?.let { sleepHandler.removeCallbacks(it) }
        originalVolume = player.volume
        val startedAt = SystemClock.elapsedRealtime()
        val plan = SleepTimerPlan(0L, fadeMs)
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                player.volume = plan.volumeAt(originalVolume, elapsed)
                if (elapsed < fadeMs) {
                    sleepHandler.postDelayed(this, minOf(200L, fadeMs - elapsed))
                } else {
                    player.pause()
                    player.volume = originalVolume
                    fadeRunnable = null
                    setPlaybackState(false)
                    updateNotification(false)
                }
            }
        }
        fadeRunnable = runnable
        sleepHandler.post(runnable)
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        fadeRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        fadeRunnable = null
        player.volume = originalVolume
    }

    private fun savePlaybackState() {
        prefs.edit()
            .putString(PREF_LAST_SONG_ID, player.currentMediaItem?.mediaId)
            .putLong(PREF_LAST_POSITION, player.currentPosition)
            .putBoolean(PREF_SHUFFLE_ENABLED, player.shuffleModeEnabled)
            .apply()
    }

    private fun restorePlaybackState(): Triple<String?, Long, Boolean> {
        return Triple(
            prefs.stringOr(PREF_LAST_SONG_ID, null),
            prefs.longOr(PREF_LAST_POSITION, 0L),
            prefs.booleanOr(PREF_SHUFFLE_ENABLED, false)
        )
    }
}
