package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.playback.VideoPlaybackConnection

/** A surface and standard controls for the service's player, with lifecycle-owned attachment. */
@Composable
fun VideoPlayerDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val connection = remember(context) { VideoPlaybackConnection(context) }
        val player by connection.player.collectAsState()
        DisposableEffect(connection, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) connection.connect()
                if (event == Lifecycle.Event.ON_STOP) connection.disconnect()
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) connection.connect()
            onDispose { lifecycle.removeObserver(observer); connection.disconnect() }
        }
        VideoPlayerContent(player, false, onDismiss)
    }
}

/** Shared full-screen surface. PiP hides app controls; the system supplies its own controls. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VideoPlayerContent(
    player: androidx.media3.common.Player?,
    inPip: Boolean,
    onDismiss: () -> Unit,
    onShrink: (() -> Unit)? = null,
    onBounds: (android.graphics.Rect) -> Unit = {},
) {
    val boundsCallback by rememberUpdatedState(onBounds)
    Column(Modifier.fillMaxSize().background(Color.Black)
        .then(if (inPip) Modifier else Modifier.safeDrawingPadding()).testTag("video_player")) {
        if (!inPip) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("video_close")) {
                Text(stringResource(R.string.video_close), color = Color.White)
            }
            if (onShrink != null) TextButton(onClick = onShrink, modifier = Modifier.testTag("video_shrink")) {
                Text(stringResource(R.string.video_shrink), color = Color.White)
            }
        }
        AndroidView(factory = { PlayerView(it).apply {
                keepScreenOn = true
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    val bounds = android.graphics.Rect()
                    if (v.getGlobalVisibleRect(bounds)) boundsCallback(bounds)
                }
            } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            update = { view ->
                view.player = player
                view.useController = !inPip
                if (inPip) view.hideController()
            }, onRelease = { it.player = null })
    }
}
