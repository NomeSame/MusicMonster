package com.nomesame.musicmonster.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps [MediaControllerCompat]: polls for the session token, registers the
 * playback callback, and exposes playback state as [StateFlow]. Transport
 * actions are proxied to the controller. Behavior matches the original
 * MainActivity.initMediaController; only the location changed.
 */
class PlaybackConnection(private val context: Context) {

    private var mediaController: MediaControllerCompat? = null
    private var boundToken: MediaSessionCompat.Token? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The polling runnable reposted until a session token is bound. Held as a
     * field so the onUnbind lambda (fired from onSessionDestroyed) can repost it
     * to reconnect after the service dies; a local val could not reference
     * itself inside its own initializer.
     */
    private lateinit var pollRunnable: Runnable
    private var callback: MediaControllerCompat.Callback? = null
    private var connected = false

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _nowPlayingTitle = MutableStateFlow<String?>(null)
    val nowPlayingTitle: StateFlow<String?> = _nowPlayingTitle.asStateFlow()

    private val _nowPlayingId = MutableStateFlow<String?>(null)
    val nowPlayingId: StateFlow<String?> = _nowPlayingId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isShuffled = MutableStateFlow(false)
    val isShuffled: StateFlow<Boolean> = _isShuffled.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /**
     * Invoked with the audio session id when it becomes available (from the
     * initial extras and on every extras change with a non-zero id). Used to
     * (re)build the equalizer.
     */
    var onAudioSession: ((Int) -> Unit)? = null

    /**
     * Polls [tokenProvider] until a session token is available, then binds the
     * controller and seeds state. Safe to call once from onCreate; auto-reconnects
     * if the service later dies and publishes a *new* token.
     *
     * The poller reposts itself only while no token is bound (or while the
     * current one was destroyed via onSessionDestroyed). Polling pauses during
     * steady-state playback so it doesn't burn battery. [onUnavailable] is
     * invoked once, after [TIMEOUT_MS], if the service never publishes a token
     * (e.g. audio permission denied) so the UI can degrade gracefully.
     */
    fun connect(
        tokenProvider: () -> MediaSessionCompat.Token?,
        onUnavailable: (() -> Unit)? = null
    ) {
        if (connected) return
        connected = true
        val startedAt = SystemClock.elapsedRealtime()
        var unavailableNotified = false
        pollRunnable = Runnable {
            if (!connected) return@Runnable
            val token = tokenProvider()
            if (token == null) {
                // Still no token: keep polling. Only the "unavailable"
                // signal is one-shot (late service starts still connect).
                if (!unavailableNotified &&
                    SystemClock.elapsedRealtime() - startedAt >= TIMEOUT_MS
                ) {
                    unavailableNotified = true
                    onUnavailable?.invoke()
                }
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            } else if (token != boundToken) {
                // A token we are not bound to yet (initial bind, or a
                // *new* token after the service restarted). Bind it.
                bindController(token) { handler.postDelayed(pollRunnable, POLL_INTERVAL_MS) }
            }
            // token == boundToken: nothing to do; polling stays parked.
        }
        handler.post(pollRunnable)
    }

    private fun bindController(
        token: MediaSessionCompat.Token,
        onUnbind: () -> Unit
    ) {
        val controller = MediaControllerCompat(context, token)
        mediaController = controller
        boundToken = token

        val registered = object : MediaControllerCompat.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                if (mediaController !== controller) return
                _isPlaying.value = state?.state == PlaybackStateCompat.STATE_PLAYING
                _position.value = (state?.position ?: 0L).coerceAtLeast(0L)
            }

            override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                if (mediaController !== controller) return
                _nowPlayingTitle.value =
                    metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                _nowPlayingId.value =
                    metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                _duration.value =
                    (metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
            }

            override fun onShuffleModeChanged(shuffleMode: Int) {
                if (mediaController !== controller) return
                _isShuffled.value = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
            }

            override fun onExtrasChanged(extras: Bundle?) {
                if (mediaController !== controller) return
                // Forward 0 too: it is the "audio session released" signal the
                // equalizer needs in order to free its native effects. Dropping
                // it here made that teardown path unreachable.
                onAudioSession?.invoke(extras?.getInt("audio_session_id") ?: 0)
            }

            override fun onSessionDestroyed() {
                // The service was killed (crash/system). Drop the dead controller
                // and resume polling so a restarting service with a fresh token
                // reconnects instead of leaving the UI stale.
                if (mediaController == controller) {
                    clearController()
                    onUnbind()
                }
            }
        }
        callback = registered
        controller.registerCallback(registered, handler)

        _isPlaying.value = controller.playbackState?.state == PlaybackStateCompat.STATE_PLAYING
        _isShuffled.value = controller.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
        _nowPlayingTitle.value = controller.metadata
            ?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        _nowPlayingId.value = controller.metadata
            ?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        _duration.value = controller.metadata
            ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        _position.value = (controller.playbackState?.position ?: 0L).coerceAtLeast(0L)
        onAudioSession?.invoke(controller.extras?.getInt("audio_session_id") ?: 0)
        _isReady.value = true
    }

    /** Releases callbacks and pending polling when the owning ViewModel ends. */
    fun disconnect() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        clearController()
    }

    private fun clearController() {
        val hadController = mediaController != null
        callback?.let { mediaController?.unregisterCallback(it) }
        callback = null
        mediaController = null
        boundToken = null
        _isReady.value = false
        _isPlaying.value = false
        _isShuffled.value = false
        _nowPlayingTitle.value = null
        _nowPlayingId.value = null
        _duration.value = 0L
        _position.value = 0L
        if (hadController) onAudioSession?.invoke(0)
    }

    /** Refreshes [position] from the controller (called by the 1s UI poller). */
    fun refreshPosition() {
        _position.value = (mediaController?.playbackState?.position ?: 0L).coerceAtLeast(0L)
    }

    fun playFromMediaId(mediaId: String) {
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    fun play() {
        mediaController?.transportControls?.play()
    }

    fun pause() {
        mediaController?.transportControls?.pause()
    }

    fun skipToNext() {
        mediaController?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        mediaController?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        mediaController?.transportControls?.seekTo(positionMs)
    }

    fun setShuffleMode(mode: Int) {
        mediaController?.transportControls?.setShuffleMode(mode)
    }

    companion object {
        /** Report unavailable once after this long; late service starts still bind. */
        private const val TIMEOUT_MS = 10_000L

        /** Poll interval while waiting for the session token (ms). */
        private const val POLL_INTERVAL_MS = 500L
    }
}
