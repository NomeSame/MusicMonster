package com.example.myapplication

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ActivityCompat
import androidx.media.session.MediaButtonReceiver

import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat



class MusicService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat

    // Keep titles in parallel with playlist for notification text
    private var titles: List<String> = emptyList()

    companion object {
        const val CHANNEL_ID = "monsterplayer_channel"
        const val NOTIFICATION_ID = 1
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
                "MonsterPlayer",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Controls for MonsterPlayer playback"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        player = ExoPlayer.Builder(this).build()

        // ✅ Load device songs (MediaStore) instead of raw
        val (items, itemTitles) = loadDevicePlaylist()
        titles = itemTitles


        if (items.isNotEmpty()) {
            player.setMediaItems(items)
            player.prepare()
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
                    updateNotification(true)
                }

                override fun onPlay() {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                    setPlaybackState(true)
                    updateSessionMetadata()
                    updateNotification(true)
                }

                override fun onPause() {
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
                    session.setShuffleMode(shuffleMode)
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
        session.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE)
        updateSessionMetadata()


        // Keep notification in sync if user changes track / state
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setPlaybackState(isPlaying)
                updateNotification(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateSessionMetadata()
                updateNotification(player.isPlaying)
            }
        })

        setPlaybackState(false)
        startForeground(NOTIFICATION_ID, buildNotification(false))
    }
    private fun updateSessionMetadata() {
        val idx = player.currentMediaItemIndex
        val currentTitle = if (idx in titles.indices) titles[idx] else "No song selected"
        val currentId = if (idx in titles.indices) player.getMediaItemAt(idx).mediaId else null

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .build()
        )
    }

    // ✅ Needed so MediaButtonReceiver PendingIntents control your MediaSession
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Returns a pair of MediaItems and their titles.  The MediaItem is built with a mediaId that matches the
    // Song.id used by the activity.
    private fun loadDevicePlaylist(): Pair<List<MediaItem>, List<String>> {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            return emptyList<MediaItem>() to emptyList()
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val items = mutableListOf<MediaItem>()
        val titles = mutableListOf<String>()

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"

                // Build MediaItem with mediaId so the activity can play by ID.
                val contentUri = ContentUris.withAppendedId(collection, id)
                items.add(MediaItem.Builder()
                    .setMediaId(id.toString())
                    .setUri(contentUri)
                    .build())
                titles.add(title)
            }
        }

        return items to titles
    }

    private fun setPlaybackState(isPlaying: Boolean) {
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                    PlaybackStateCompat.ACTION_STOP

        val state =
            if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, player.currentPosition, 1f)
                .build()
        )
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
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

        val idx = player.currentMediaItemIndex
        val currentTitle = if (idx in titles.indices) titles[idx] else "No song selected"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            // Use the current track title as the notification title so that
            // the lock‑screen banner displays the same name as the song
            // actually playing.  The contentText is left empty to make the
            // title appear larger on the lock screen.
            .setContentTitle(currentTitle)
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pendingIntentPrev)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", pendingIntentPlayPause)
            .addAction(android.R.drawable.ic_media_next, "Next", pendingIntentNext)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification(isPlaying: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(isPlaying))
    }
}
