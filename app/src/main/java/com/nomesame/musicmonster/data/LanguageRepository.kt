package com.nomesame.musicmonster.data

import android.content.Context
import android.content.SharedPreferences
import com.nomesame.musicmonster.model.AppLanguage

/** Device-independent preference, separate from non-transferable music/URI settings. */
class LanguageRepository(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun load(): AppLanguage = AppLanguage.fromTag(prefs.stringOr(KEY_LANGUAGE, null))

    fun save(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag).apply()
    }

    companion object {
        const val PREFS_NAME = "language_prefs"
        const val KEY_LANGUAGE = "app_language"
    }
}
