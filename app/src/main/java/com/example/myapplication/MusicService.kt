package com.example.myapplication

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import android.net.Uri
import android.util.Log

/**
 * A minimal foreground service that keeps ExoPlayer running in background.
 * For a production app you would add a notification and proper lifecycle handling.
 */
class MusicService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        val context = this
        player = ExoPlayer.Builder(context).build()
        // Example media item – replace with your own playlist logic
        val uri = Uri.parse("android.resource://${context.packageName}/raw/song1")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        session = MediaSessionCompat(context, "MusicBoxService")
        session.isActive = true
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { player.play() }
            override fun onPause() { player.pause() }
            override fun onSkipToNext() { player.seekTo(0) }
            override fun onSkipToPrevious() { player.seekTo(0) }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        session.isActive = false
        player.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
