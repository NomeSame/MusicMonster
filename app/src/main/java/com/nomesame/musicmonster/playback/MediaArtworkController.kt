package com.nomesame.musicmonster.playback

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import com.nomesame.musicmonster.model.MediaAppearance
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Service-owned artwork lifecycle: observe design, debounce I/O, reject late/stale results. */
class MediaArtworkController(
    private val repository: MediaAppearanceRepository,
    private val load: (MediaAppearance) -> Bitmap?,
    private val onChanged: (artworkChanged: Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var unsubscribe: (() -> Unit)? = null
    private var appearance: MediaAppearance? = null
    private var pending: Future<*>? = null
    private var scheduled: Runnable? = null
    private var generation = 0L
    private var closed = false
    var artwork: Bitmap? = null
        private set

    fun start() {
        if (closed || unsubscribe != null) return
        unsubscribe = repository.observe(::refresh)
        refresh()
    }

    private fun refresh() {
        if (closed) return
        val next = repository.load()
        if (next == appearance) return
        val imageChanged = next.imageKey != appearance?.imageKey
        appearance = next
        if (!imageChanged) {
            onChanged(false)
            return
        }
        val request = ++generation
        scheduled?.let(main::removeCallbacks)
        pending?.cancel(false)
        if (!next.backgroundEnabled) {
            artwork = null
            onChanged(true)
            return
        }
        // Update the accent immediately while keeping the previous image until replacement is ready.
        onChanged(false)
        scheduled = Runnable {
            pending = worker.submit {
                val loaded = runCatching { load(next) }.getOrNull()
                main.post {
                    if (!closed && request == generation) {
                        artwork = loaded
                        onChanged(true)
                    } else {
                        loaded?.recycle()
                    }
                }
            }
        }.also { main.postDelayed(it, 120L) }
    }

    fun close() {
        if (closed) return
        closed = true
        ++generation
        unsubscribe?.invoke()
        unsubscribe = null
        scheduled?.let(main::removeCallbacks)
        pending?.cancel(false)
        worker.shutdownNow()
        // Published bitmaps may still be held by framework notifications/session metadata.
        // Do not recycle them eagerly; allow their last owner to release them normally.
        artwork = null
    }
}
