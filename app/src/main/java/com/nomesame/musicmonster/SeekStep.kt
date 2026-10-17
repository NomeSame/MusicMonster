package com.nomesame.musicmonster

/** Shared interval contract and overflow-safe jumps within a single song. */
object SeekStep {
    const val DEFAULT_SECONDS = 10
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 300
    const val STEP_SECONDS = 5
    val options: List<Int> = (MIN_SECONDS..MAX_SECONDS step STEP_SECONDS).toList()

    fun validSeconds(value: Int): Int =
        if (value in MIN_SECONDS..MAX_SECONDS && value % STEP_SECONDS == 0) value
        else DEFAULT_SECONDS

    /** Stay before the end so repeat/shuffle cannot turn a jump into a song change. */
    fun target(positionMs: Long, durationMs: Long, seconds: Int, forward: Boolean): Long? {
        if (durationMs <= 0L) return null
        val lastPosition = durationMs - 1L
        val position = positionMs.coerceIn(0L, lastPosition)
        val delta = validSeconds(seconds) * 1000L
        return if (forward) position + minOf(delta, lastPosition - position)
        else position - minOf(delta, position)
    }
}
