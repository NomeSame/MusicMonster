package com.nomesame.musicmonster.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The app's semantic color roles. Surfaces are a fixed neutral-dark set so any
 * user-chosen [accent] reads well; only [accent] (and its derived shades) change
 * when the user picks a different color.
 *
 * Build with [darkAppColors]; read in composables via [LocalAppColors].
 */
@Immutable
data class AppColors(
    val backgroundGradient: List<Color>,
    val panel: Color,
    val panelBorder: Color,
    val divider: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color
)

/** Default Music Monster accent (warm amber). */
val DefaultAccent = Color(0xFFFFB14A)

/** Neutral-dark surface roles combined with the given [accent]. */
fun darkAppColors(accent: Color): AppColors = AppColors(
    backgroundGradient = listOf(
        Color(0xFF121316),
        Color(0xFF0D0E11),
        Color(0xFF0A0B0D)
    ),
    panel = Color(0xFF17191D),
    panelBorder = Color(0xFF282C33),
    divider = Color(0xFF20232A),
    textPrimary = Color(0xFFECEDEF),
    textMuted = Color(0xFF9BA1A8),
    accent = accent,
    // A dimmer shade of the accent for secondary/active-but-quiet elements.
    accentSoft = lerp(accent, Color(0xFF101114), 0.35f),
    // Foreground for use on top of accent — dark for light accents, light for dark.
    onAccent = if (0.2126f * accent.red + 0.7152f * accent.green + 0.0722f * accent.blue > 0.5f) Color(0xFF141414) else Color(0xFFECEDEF)
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors(DefaultAccent) }
