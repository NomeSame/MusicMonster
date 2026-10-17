package com.nomesame.musicmonster.playback

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.nomesame.musicmonster.R

/** Platform PiP adapter. The service retains sole ownership of the playback player. */
class VideoPipController(private val activity: Activity) : AutoCloseable {
    val supported = Build.VERSION.SDK_INT >= 26 &&
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private var player: Player? = null
    private var bounds: Rect? = null
    private var enabled = false
    private val action = activity.packageName + ".VIDEO_PIP_TOGGLE"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == action && activity.isInPictureInPictureMode) {
                player?.let { if (it.playWhenReady) it.pause() else it.play() }
            }
        }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update() }
    }

    init {
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun attach(value: Player?) {
        player?.removeListener(listener)
        player = value
        value?.addListener(listener)
        enabled = value != null
        update()
    }

    fun sourceBounds(value: Rect) { bounds = Rect(value); update() }
    fun disable() { enabled = false; update() }

    fun enter(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !supported || !enabled || activity.isFinishing) return false
        return runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
    }

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < 31) enter() // Android 12+ handles the Home gesture itself.
    }

    private fun update() {
        if (Build.VERSION.SDK_INT >= 26 && supported && !activity.isFinishing)
            runCatching { activity.setPictureInPictureParams(params()) }
    }

    @androidx.annotation.RequiresApi(26)
    private fun params(): PictureInPictureParams {
        val video = player?.videoSize
        val ratio = if (video != null && video.width > 0 && video.height > 0)
            (video.width * video.pixelWidthHeightRatio / video.height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
        val playing = player?.playWhenReady == true
        val label = activity.getString(if (playing) R.string.pause else R.string.play)
        val pending = PendingIntent.getBroadcast(activity, 0, Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = RemoteAction(Icon.createWithResource(activity,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), label, label, pending)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(toggle)).apply {
                bounds?.takeIf { !it.isEmpty }?.let { setSourceRectHint(it) }
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(enabled)
            }.build()
    }

    override fun close() {
        disable()
        player?.removeListener(listener)
        player = null
        activity.unregisterReceiver(receiver)
    }
}
