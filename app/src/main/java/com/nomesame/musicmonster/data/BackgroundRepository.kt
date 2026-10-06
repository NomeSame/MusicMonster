package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import android.net.Uri

/**
 * Persists the user's custom-background settings in SharedPreferences:
 * the master on/off toggle, the chosen image URI, and the dim/scrim strength.
 * Pure data access — no Android UI, no bitmap work (that lives in PaletteEngine
 * / AppBackground). Follows the repository rule from ARCHITECTURE.md.
 */
class BackgroundRepository(private val prefs: SharedPreferences) {

    // Defaults to true: the app ships showing the bundled default background
    // image; the toggle turns the image (default or custom) off in favor of the
    // plain gradient.
    fun isEnabled(): Boolean = prefs.booleanOr(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun uri(): Uri? = runCatching {
        prefs.stringOr(KEY_URI, null)?.let(Uri::parse)
    }.getOrNull()

    fun setUri(uri: Uri?) {
        prefs.edit().apply {
            if (uri == null) remove(KEY_URI) else putString(KEY_URI, uri.toString())
        }.apply()
    }

    /** Dim strength of the dark scrim over the image, 0f (bright) .. 1f (black). */
    fun scrim(): Float = prefs.floatOr(KEY_SCRIM, DEFAULT_SCRIM).coerceIn(0f, 1f)

    fun setScrim(value: Float) {
        prefs.edit().putFloat(KEY_SCRIM, unitFloat(value, DEFAULT_SCRIM)).apply()
    }

    companion object {
        const val KEY_ENABLED = "custom_bg_enabled"
        const val KEY_URI = "custom_bg_uri"
        const val KEY_SCRIM = "custom_bg_scrim"
        // ~75% on the dim slider (right edge = 100% = fully dark).
        const val DEFAULT_SCRIM = 0.75f
    }
}
