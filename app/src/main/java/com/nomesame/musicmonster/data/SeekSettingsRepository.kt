package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import com.nomesame.musicmonster.SeekStep

/** One persistent interval shared by rewind and forward. */
class SeekSettingsRepository(private val prefs: SharedPreferences) {
    fun buttonsEnabled(): Boolean = prefs.booleanOr(KEY_BUTTONS_ENABLED, true)

    fun setButtonsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUTTONS_ENABLED, enabled).apply()
    }

    fun loadSeconds(): Int = SeekStep.validSeconds(prefs.intOr(KEY_SECONDS, SeekStep.DEFAULT_SECONDS))

    fun saveSeconds(seconds: Int): Int {
        val valid = SeekStep.validSeconds(seconds)
        prefs.edit().putInt(KEY_SECONDS, valid).apply()
        return valid
    }

    companion object {
        const val KEY_BUTTONS_ENABLED = "seek_buttons_enabled"
        const val KEY_SECONDS = "seek_step_seconds"
    }
}
