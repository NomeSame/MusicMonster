package com.nomesame.musicmonster.data

import android.content.SharedPreferences

/**
 * Type-tolerant SharedPreferences reads.
 *
 * `SharedPreferences.getInt` and friends answer a type mismatch with
 * `ClassCastException`, not with the supplied default. A single key holding the
 * wrong type — an older build that stored it differently, a restored backup, a
 * hand-edited prefs file on a rooted device — therefore takes the app down on
 * launch, before any UI exists to report it. Every read in this app goes
 * through these helpers so that a corrupt value degrades to the default instead
 * of being fatal.
 *
 * Pinned by AppLifecycleInstrumentedTest.launchesWithTypeMismatchedPreferences.
 */

fun SharedPreferences.intOr(key: String, default: Int): Int =
    runCatching { getInt(key, default) }.getOrDefault(default)

fun SharedPreferences.longOr(key: String, default: Long): Long =
    runCatching { getLong(key, default) }.getOrDefault(default)

fun SharedPreferences.floatOr(key: String, default: Float): Float =
    runCatching { getFloat(key, default).takeIf { it.isFinite() } ?: default }.getOrDefault(default)

fun SharedPreferences.booleanOr(key: String, default: Boolean): Boolean =
    runCatching { getBoolean(key, default) }.getOrDefault(default)

fun SharedPreferences.stringOr(key: String, default: String?): String? =
    runCatching { getString(key, default) }.getOrDefault(default)

/** Finite UI alpha in the unit interval, with a safe fallback for NaN/infinity. */
internal fun unitFloat(value: Float, default: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else default
