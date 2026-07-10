package com.nomesame.musicmonster

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.content.SharedPreferences


@UnstableApi
class MusicService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
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
    private var libraryDurations: Map<String, Long> = emptyMap()
    private var currentQueueIds: List<String> = emptyList()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("music_prefs", MODE_PRIVATE)
    }

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
        /**
         * Holds the session token once the service has created its MediaSession.
         * Activities can read this to construct a {@link MediaControllerCompat}.
         */
        @Volatile
        var sessionToken: MediaSessionCompat.Token? = null

    }

    override fun onCreate() {
        super.onCreate()

        // Notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Monster",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Controls for Music Monster playback"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        player = ExoPlayer.Builder(this).build()
        player.repeatMode = Player.REPEAT_MODE_ALL

        val (items, itemTitles, itemDurations) = loadDevicePlaylist()
        titles = itemTitles
        libraryItems = items.associateBy { it.mediaId }
        libraryTitles = items.zip(itemTitles).associate { it.first.mediaId to it.second }
        libraryDurations = itemDurations
        currentQueueIds = items.map { it.mediaId }


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
                player.seekTo(targetIndex, lastPos)
            }
        }

        session = MediaSessionCompat(this, "MonsterPlayerService").apply {
            isActive = true
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == null) return

                    val targetIndex = (0 until player.mediaItemCount)
                        .firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
                        ?: return

                    player.seekTo(targetIndex, 0L)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()

                    setPlaybackState(true)
                    updateSessionMetadata()
                    updateSessionExtras()
                    updateNotification(true)
                }

                override fun onPlay() {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                    setPlaybackState(true)
                    updateSessionMetadata()
                    updateSessionExtras()
                    updateNotification(true)
                }

                override fun onPause() {
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
        startForeground(NOTIFICATION_ID, buildNotification(false))
    }
    private fun updateSessionMetadata() {
        val idx = player.currentMediaItemIndex
        val currentTitle = if (idx in titles.indices) titles[idx] else "No song selected"
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
        val sessionId = player.audioSessionId
        if (sessionId != 0) {
            session.setExtras(Bundle().apply {
                putInt("audio_session_id", sessionId)
            })
        }
    }

    // Handles action intents (e.g. from notification/lock-screen PendingIntents)
    // delivered to this service, since it isn't a bound service.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            reloadPlaylistPreservingCurrent()
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
            setPlaylistAndPlay(ids, startId)
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
        player.release()
        positionHandler.removeCallbacks(positionUpdate)
        cancelSleepTimer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Returns MediaItems, titles, and durations (ms) keyed by mediaId.
    private fun loadDevicePlaylist(): Triple<List<MediaItem>, List<String>, Map<String, Long>> {
        val treeUri = prefs.getString("library_tree_uri", null)?.let { Uri.parse(it) }
        if (treeUri != null) {
            return loadTreePlaylist(treeUri)
        }
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            return Triple(emptyList(), emptyList(), emptyMap())
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
val sortOrder = null

        val items = mutableListOf<MediaItem>()
        val titles = mutableListOf<String>()
        val durations = mutableMapOf<String, Long>()

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val duration = cursor.getLong(durationCol)

                // Build MediaItem with mediaId so the activity can play by ID.
                val contentUri = ContentUris.withAppendedId(collection, id)
                val mediaId = id.toString()
                items.add(MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(contentUri)
                    .build())
                titles.add(title)
                durations[mediaId] = duration
            }
        }

        val combined = items.zip(titles).sortedWith(naturalTitleComparator())
        val sortedItems = combined.map { it.first }
        val sortedTitles = combined.map { it.second }
        return Triple(sortedItems, sortedTitles, durations)
    }

    private fun loadTreePlaylist(treeUri: Uri): Triple<List<MediaItem>, List<String>, Map<String, Long>> {
        val root = DocumentFile.fromTreeUri(this, treeUri)
            ?: return Triple(emptyList(), emptyList(), emptyMap())
        val stack = ArrayDeque<DocumentFile>()
        val items = mutableListOf<MediaItem>()
        val titles = mutableListOf<String>()
        val durations = mutableMapOf<String, Long>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val doc = stack.removeFirst()
            if (doc.isDirectory) {
                doc.listFiles().forEach { stack.add(it) }
            } else {
                val name = doc.name ?: continue
                val type = doc.type
                val isAudio = type?.startsWith("audio/") == true ||
                    name.endsWith(".mp3", true) ||
                    name.endsWith(".m4a", true) ||
                    name.endsWith(".flac", true) ||
                    name.endsWith(".wav", true) ||
                    name.endsWith(".ogg", true)
                if (isAudio) {
                    val title = name.substringBeforeLast('.')
                    val uri = doc.uri
                    val mediaId = uri.toString()
                    items.add(
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(uri)
                            .build()
                    )
                    titles.add(title)
                    durations[mediaId] = 0L
                }
            }
        }
        val combined = items.zip(titles).sortedWith(naturalTitleComparator())
        val sortedItems = combined.map { it.first }
        val sortedTitles = combined.map { it.second }
        return Triple(sortedItems, sortedTitles, durations)
    }

    private fun naturalTitleComparator(): Comparator<Pair<MediaItem, String>> = Comparator { (_, a), (_, b) ->
    compareNatural(a.lowercase(), b.lowercase())
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
                    player.currentPosition,
                    if (isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun accentColorInt(): Int =
        getSharedPreferences("music_prefs", MODE_PRIVATE)
            .getInt("accent_color", Color.parseColor("#E58B3C"))

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
        val currentTitle = if (idx in titles.indices) titles[idx] else "No song selected"
        val shuffleOn = player.shuffleModeEnabled
        val shuffleLabel = if (shuffleOn) "Shuffle On" else "Shuffle Off"

        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val contentView = RemoteViews(packageName, R.layout.notification_music_monster).apply {
            setTextViewText(R.id.notif_title, currentTitle)
            setTextViewText(R.id.notif_time_current, formatTime(position))
            setTextViewText(R.id.notif_time_duration, formatTime(duration))
            setProgressBar(
                R.id.notif_progress,
                duration.toInt().coerceAtLeast(1),
                position.toInt().coerceAtLeast(0),
                false
            )
        }

        val publicNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(currentTitle)
            .setContentText("")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setColor(accentInt)
            .setColorized(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pendingIntentPrev)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", pendingIntentPlayPause)
            .addAction(android.R.drawable.ic_media_next, "Next", pendingIntentNext)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
            .addAction(android.R.drawable.ic_media_previous, "Previous", pendingIntentPrev)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", pendingIntentPlayPause)
            .addAction(android.R.drawable.ic_media_next, "Next", pendingIntentNext)
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

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
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

    private fun reloadPlaylistPreservingCurrent() {
        val currentId = player.currentMediaItem?.mediaId
        val currentPosition = player.currentPosition
        val (items, itemTitles, itemDurations) = loadDevicePlaylist()
        titles = itemTitles
        libraryItems = items.associateBy { it.mediaId }
        libraryTitles = items.zip(itemTitles).associate { it.first.mediaId to it.second }
        libraryDurations = itemDurations
        currentQueueIds = items.map { it.mediaId }
        if (items.isEmpty()) {
            player.stop()
            updateSessionMetadata()
            updateNotification(false)
            return
        }

        val targetIndex = currentId?.let { id ->
            items.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 }
        } ?: 0

        player.setMediaItems(items, targetIndex, currentPosition)
        player.prepare()
        if (player.shuffleModeEnabled) {
            reshufflePlaylist()
        }
        updateSessionMetadata()
        setPlaybackState(player.isPlaying)
        updateNotification(player.isPlaying)
    }

    private fun setPlaylistAndPlay(ids: List<String>, startId: String?) {
        if (ids.isEmpty()) return
        val items = ids.mapNotNull { libraryItems[it] }
        if (items.isEmpty()) return
        val titlesForPlaylist = ids.mapNotNull { libraryTitles[it] }
        titles = titlesForPlaylist
        currentQueueIds = ids
        val startIndex = startId?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: 0
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

    private fun buildShuffleSeed(): Long {
        val maxDuration = currentQueueIds.maxOfOrNull { libraryDurations[it] ?: 0L } ?: 0L
        return System.currentTimeMillis() + maxDuration + SystemClock.elapsedRealtime()
    }

    private fun startSleepTimer(durationMs: Long, fadeMs: Long) {
        cancelSleepTimer()
        val safeFadeMs = fadeMs.coerceAtMost(durationMs)
        val waitMs = (durationMs - safeFadeMs).coerceAtLeast(0L)
        sleepRunnable = Runnable {
            if (safeFadeMs > 0L) {
                startFadeOut(safeFadeMs)
            } else {
                player.pause()
                setPlaybackState(false)
                updateNotification(false)
            }
        }
        sleepHandler.postDelayed(sleepRunnable!!, waitMs)
    }

    private fun startFadeOut(fadeMs: Long) {
        fadeRunnable?.let { sleepHandler.removeCallbacks(it) }
        originalVolume = player.volume
        val steps = (fadeMs / 200L).coerceAtLeast(1L).toInt()
        var step = 0
        val stepDuration = fadeMs / steps
        val runnable = object : Runnable {
            override fun run() {
                step++
                val progress = step / steps.toFloat()
                player.volume = originalVolume * (1f - progress).coerceIn(0f, 1f)
                if (step < steps) {
                    sleepHandler.postDelayed(this, stepDuration)
                } else {
                    player.pause()
                    player.volume = originalVolume
                    setPlaybackState(false)
                    updateNotification(false)
                }
            }
        }
        fadeRunnable = runnable
        sleepHandler.post(runnable)
    }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var numA = 0L; while (i < a.length && a[i].isDigit()) { numA = numA * 10 + (a[i] - '0'); i++ }
                var numB = 0L; while (j < b.length && b[j].isDigit()) { numB = numB * 10 + (b[j] - '0'); j++ }
                if (numA != numB) return numA.compareTo(numB)
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++; j++
            }
        }
        if (i < a.length) return 1
        if (j < b.length) return -1
        return 0
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
            prefs.getString(PREF_LAST_SONG_ID, null),
            prefs.getLong(PREF_LAST_POSITION, 0L),
            prefs.getBoolean(PREF_SHUFFLE_ENABLED, false)
        )
    }
}
