package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import com.nomesame.musicmonster.model.LibraryPreset

class LibrarySettingsRepository(private val prefs: SharedPreferences) {
    fun preset(): LibraryPreset = LibraryPreset.entries.firstOrNull { it.name == prefs.stringOr("library_preset", null) }
        ?: LibraryPreset.VIDEOS_FIRST
    fun browsing() = prefs.booleanOr("library_browse_folders", false)
    fun save(preset: LibraryPreset, browsing: Boolean) {
        prefs.edit().putString("library_preset", preset.name).putBoolean("library_browse_folders", browsing).apply()
    }
}
