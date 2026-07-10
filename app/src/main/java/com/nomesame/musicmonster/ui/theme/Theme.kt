package com.nomesame.musicmonster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Dark-only, accent-driven theme. The user-chosen [accent] flows into both the
 * Material [darkColorScheme] (so stock components pick it up) and the extended
 * [AppColors] exposed via [LocalAppColors]. Surfaces stay neutral-dark.
 */
@Composable
fun MyApplicationTheme(
    accent: Color = DefaultAccent,
    content: @Composable () -> Unit
) {
    val appColors = darkAppColors(accent)

    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = appColors.onAccent,
        secondary = accent,
        onSecondary = appColors.onAccent,
        tertiary = accent,
        onTertiary = appColors.onAccent,
        background = appColors.backgroundGradient.last(),
        onBackground = appColors.textPrimary,
        surface = appColors.panel,
        onSurface = appColors.textPrimary,
        surfaceVariant = appColors.panel,
        onSurfaceVariant = appColors.textMuted,
        outline = appColors.panelBorder,
        outlineVariant = appColors.divider
    )

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
