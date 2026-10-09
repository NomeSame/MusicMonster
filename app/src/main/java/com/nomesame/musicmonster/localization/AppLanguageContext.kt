package com.nomesame.musicmonster.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.nomesame.musicmonster.model.AppLanguage

/** Scoped resource override: never mutates system resources or Locale.getDefault(). */
fun appLanguageContext(context: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return context
    val config = Configuration(context.resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(language.tag))
    return context.createConfigurationContext(config)
}
