package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import com.nomesame.musicmonster.model.MediaAppearance
import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.ArtworkCropGeometry

/** Read and observe the same persisted design settings used by the app. */
class MediaAppearanceRepository(private val prefs: SharedPreferences) {
    private val background = BackgroundRepository(prefs)

    fun load() = MediaAppearance(background.isEnabled(), background.uri()?.toString(),
        background.scrim(), prefs.intOr(KEY_ACCENT, DEFAULT_ACCENT), loadCrop())

    fun loadCrop(): ArtworkCrop {
        val defaults = ArtworkCrop.defaultForBackground(background.uri() != null)
        return ArtworkCrop(prefs.floatOr(KEY_CROP_X, defaults.x).coerceIn(0f, 1f),
            prefs.floatOr(KEY_CROP_Y, defaults.y).coerceIn(0f, 1f),
            prefs.floatOr(KEY_CROP_ZOOM, defaults.zoom).coerceIn(1f, 4f))
    }

    fun saveCrop(crop: ArtworkCrop) {
        val position = ArtworkCropGeometry.normalized(crop)
        prefs.edit().putFloat(KEY_CROP_X, position.x).putFloat(KEY_CROP_Y, position.y)
            .putFloat(KEY_CROP_ZOOM, position.zoom).apply()
    }

    /** Caller retains the unsubscribe closure (and thereby Android's weakly held listener). */
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val KEY_CROP_ZOOM = "media_artwork_crop_zoom"
        const val KEY_CROP_X = "media_artwork_crop_x"
        const val KEY_CROP_Y = "media_artwork_crop_y"
        const val KEY_ACCENT = "accent_color"
        val DEFAULT_ACCENT: Int = 0xFFE58B3C.toInt()
        private val KEYS = setOf(KEY_ACCENT, BackgroundRepository.KEY_ENABLED,
            BackgroundRepository.KEY_URI, BackgroundRepository.KEY_SCRIM, KEY_CROP_X, KEY_CROP_Y, KEY_CROP_ZOOM)
    }
}
