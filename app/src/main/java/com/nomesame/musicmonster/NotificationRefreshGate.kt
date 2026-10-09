package com.nomesame.musicmonster

/** Bound notification traffic without postponing the newest state indefinitely during a burst. */
class NotificationRefreshGate {
    private var lastPostedMs: Long? = null

    fun delayAt(nowMs: Long): Long {
        val last = lastPostedMs ?: return 0L
        val now = nowMs.coerceAtLeast(0L)
        val elapsed = if (now >= last) now - last else 0L
        return (INTERVAL_MS - elapsed.coerceAtMost(INTERVAL_MS)).coerceAtLeast(0L)
    }

    fun markPosted(nowMs: Long) {
        lastPostedMs = nowMs.coerceAtLeast(0L)
    }

    companion object {
        const val INTERVAL_MS = 500L
    }
}
