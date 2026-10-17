package com.nomesame.musicmonster.playback

import android.os.Handler
import android.os.Looper
import com.nomesame.musicmonster.data.PlaybackStateStore
import com.nomesame.musicmonster.model.PlaybackResumeState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Main-thread sampling, bounded latest-value queue, serialized durable writes off main. */
class PlaybackCheckpointController(
    private val repository: PlaybackStateStore,
    private val snapshot: () -> PlaybackResumeState?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val lock = Any()
    private var latest: PlaybackResumeState? = null
    private var pending: PlaybackResumeState? = null
    private var draining = false
    private var closed = false
    private var playing = false
    @Volatile var lastPersisted: PlaybackResumeState? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (closed || !playing) return
            saveNow()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun load(): PlaybackResumeState = synchronized(lock) { latest } ?: repository.load()

    fun setPlaying(value: Boolean) {
        if (closed) return
        playing = value
        handler.removeCallbacks(tick)
        saveNow()
        if (value) handler.postDelayed(tick, INTERVAL_MS)
    }

    fun saveNow() {
        if (closed) return
        val state = snapshot()?.takeIf { !it.songId.isNullOrBlank() } ?: return
        synchronized(lock) {
            latest = state
            if (state == lastPersisted && !draining) return
            pending = state
            if (draining) return
            draining = true
            worker.execute { drain() }
        }
    }

    private fun drain() {
        while (true) {
            val state = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) draining = false
                next
            } ?: return
            if (state != lastPersisted && runCatching { repository.save(state) }.getOrDefault(false)) {
                lastPersisted = state
            }
            // Failed writes are not marked persisted; the next checkpoint retries.
        }
    }

    /** The service captures its final snapshot before close; queued work can finish. */
    fun close() {
        if (closed) return
        closed = true
        playing = false
        handler.removeCallbacks(tick)
        worker.shutdown()
    }

    companion object {
        const val INTERVAL_MS = 5000L
    }
}
