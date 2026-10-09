package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import com.nomesame.musicmonster.model.MediaAppearance

/** Read and observe the same persisted design settings used by the app. */
class MediaAppearanceRepository(private val prefs: SharedPreferences) {
    private val background = BackgroundRepository(prefs)

    fun load() = MediaAppearance(background.isEnabled(), background.uri()?.toString(),
        background.scrim(), prefs.intOr(KEY_ACCENT, DEFAULT_ACCENT))

    /** Caller retains the unsubscribe closure (and thereby Android's weakly held listener). */
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val KEY_ACCENT = "accent_color"
        val DEFAULT_ACCENT: Int = 0xFFE58B3C.toInt()
        private val KEYS = setOf(KEY_ACCENT, BackgroundRepository.KEY_ENABLED,
            BackgroundRepository.KEY_URI, BackgroundRepository.KEY_SCRIM)
    }
}
