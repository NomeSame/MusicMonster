package com.nomesame.musicmonster.model

/** Stable preference values; labels belong to translated UI resources. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""), ENGLISH("en"), GERMAN("de");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
