package com.nomesame.musicmonster

/** Framework-free scheduling boundaries used by the playback service. */
internal data class SleepTimerPlan(val waitMs: Long, val fadeMs: Long) {
    fun volumeAt(original: Float, elapsedMs: Long): Float {
        if (fadeMs == 0L) return 0f
        val progress = elapsedMs.coerceIn(0L, fadeMs).toDouble() / fadeMs
        return (original * (1.0 - progress)).toFloat()
    }

    companion object {
        fun create(durationMs: Long, fadeMs: Long): SleepTimerPlan? {
            if (durationMs <= 0L) return null
            val safeFade = fadeMs.coerceIn(0L, durationMs)
            return SleepTimerPlan((durationMs - safeFade).coerceAtLeast(0L), safeFade)
        }
    }
}
