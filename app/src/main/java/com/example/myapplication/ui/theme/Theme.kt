package com.example.myapplication.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MonsterGreenLight,
    onPrimary = MonsterNight,
    secondary = MonsterGreenMuted,
    onSecondary = MonsterNight,
    tertiary = MonsterClayLight,
    onTertiary = MonsterNight,
    background = MonsterNight,
    onBackground = MonsterNightOn,
    surface = MonsterNightSurface,
    onSurface = MonsterNightOn,
    surfaceVariant = MonsterNightVariant,
    onSurfaceVariant = MonsterNightOnVariant,
    outline = MonsterNightOutline,
    outlineVariant = MonsterNightOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = MonsterGreen,
    onPrimary = MonsterSand,
    secondary = MonsterGreenDark,
    onSecondary = MonsterSand,
    tertiary = MonsterClay,
    onTertiary = MonsterSand,
    background = MonsterSand,
    onBackground = MonsterInk,
    surface = MonsterSurface,
    onSurface = MonsterInk,
    surfaceVariant = MonsterSurfaceVariant,
    onSurfaceVariant = MonsterOutline,
    outline = MonsterOutline,
    outlineVariant = MonsterOutlineVariant
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
