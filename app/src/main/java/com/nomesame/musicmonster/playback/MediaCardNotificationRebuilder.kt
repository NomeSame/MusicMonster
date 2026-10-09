package com.nomesame.musicmonster.playback

import android.os.Handler
import android.os.Looper

/** Recreates a stale OEM card without replacing the player or media session.
 * MIUI Android 13 ignores artwork-only updates when title/artist stay unchanged.
 * Calls run on the service main thread; restore reads the latest service state.
 */
class MediaCardNotificationRebuilder(
    private val enabled: Boolean,
    private val removeCard: () -> Unit,
    private val restoreCard: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private var waiting = false
    private var closed = false
    private val restore = Runnable {
        if (!closed) {
            waiting = false
            restoreCard()
        }
    }

    fun publish(artworkChanged: Boolean, postUpdate: () -> Unit) {
        if (closed || waiting) return
        if (enabled && artworkChanged) {
            waiting = true
            removeCard()
            handler.postDelayed(restore, RECREATE_DELAY_MS)
        } else {
            postUpdate()
        }
    }

    fun close() {
        closed = true
        waiting = false
        handler.removeCallbacks(restore)
    }

    companion object {
        const val RECREATE_DELAY_MS = 750L
        fun isAffected(manufacturer: String, sdk: Int): Boolean =
            manufacturer.equals("Xiaomi", ignoreCase = true) && sdk == 33
    }
}