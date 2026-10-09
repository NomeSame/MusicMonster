package com.nomesame.musicmonster.ui.components

import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.ui.theme.AppSpacing
import com.nomesame.musicmonster.ui.theme.LocalAppColors

private val PresetAccents = listOf(
    Color(0xFFFFB14A), // amber (default)
    Color(0xFFFF6B6B), // coral
    Color(0xFFF06595), // pink
    Color(0xFFCC5DE8), // purple
    Color(0xFF5C7CFA), // indigo
    Color(0xFF4DABF7), // blue
    Color(0xFF22B8CF), // cyan
    Color(0xFF51CF66), // green
    Color(0xFFFCC419)  // yellow
)

private fun colorToHsv(color: Color): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv
}

/**
 * A dialog to pick the app accent color: preset swatches plus full HSV control
 * (rainbow hue bar + saturation + brightness). [onAccentChange] fires live so
 * the whole app recolors as the user drags.
 */
@Composable
fun AccentPickerDialog(
    current: Color,
    onAccentChange: (Color) -> Unit,
    onDismiss: () -> Unit,
    customBgEnabled: Boolean = false,
    onCustomBgEnabledChange: (Boolean) -> Unit = {},
    hasCustomBgImage: Boolean = false,
    bgScrim: Float = 0.75f,
    onBgScrimChange: (Float) -> Unit = {},
    onPickBackground: () -> Unit = {},
    onResetBackground: () -> Unit = {},
    playerOpacity: Float = 1f,
    onPlayerOpacityChange: (Float) -> Unit = {}
) {
    val colors = LocalAppColors.current
    val initial = remember { colorToHsv(current) }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var sat by remember { mutableFloatStateOf(initial[1].coerceAtLeast(0.15f)) }
    var value by remember { mutableFloatStateOf(initial[2].coerceAtLeast(0.4f)) }

    val selected = Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

    LaunchedEffect(selected) { onAccentChange(selected) }

    val rainbow = remember {
        Brush.horizontalGradient(
            (0..6).map { Color.hsv(it * 60f, 0.85f, 1f) }
        )
    }

    AlertDialog(
        containerColor = colors.panel,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.accent_color), color = colors.textPrimary) },
        text = {
            Column {
                // Live preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selected)
                        .border(1.dp, colors.panelBorder, RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(AppSpacing.md))

                // Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PresetAccents.forEach { preset ->
                        val isSelected = preset.toArgb() == selected.toArgb()
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(preset)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) colors.textPrimary else colors.panelBorder,
                                    shape = CircleShape
                                )
                                .clickable {
                                    val hsv = colorToHsv(preset)
                                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                }
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.md))

                // Hue over a rainbow bar
                Text(text = stringResource(R.string.hue), color = colors.textMuted)
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(rainbow)
                    )
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            thumbColor = selected,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent
                        )
                    )
                }

                Text(text = stringResource(R.string.saturation), color = colors.textMuted)
                Slider(
                    value = sat,
                    onValueChange = { sat = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = selected,
                        activeTrackColor = selected,
                        inactiveTrackColor = colors.divider
                    )
                )

                Text(text = stringResource(R.string.brightness), color = colors.textMuted)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = selected,
                        activeTrackColor = selected,
                        inactiveTrackColor = colors.divider
                    )
                )

                Spacer(Modifier.height(AppSpacing.md))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.divider)
                )
                Spacer(Modifier.height(AppSpacing.md))

                // --- Custom background ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.custom_background),
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = customBgEnabled,
                        onCheckedChange = onCustomBgEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = selected,
                            checkedTrackColor = selected.copy(alpha = 0.4f),
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.divider
                        )
                    )
                }

                if (customBgEnabled) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedButton(
                        onClick = onPickBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ControlLabel(
                            text = if (hasCustomBgImage) stringResource(R.string.change_image) else stringResource(R.string.choose_image),
                            color = selected
                        )
                    }
                    if (hasCustomBgImage) {
                        TextButton(
                            onClick = onResetBackground,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ControlLabel(text = stringResource(R.string.default_image), color = colors.textMuted)
                        }
                    }

                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(text = stringResource(R.string.background_dim), color = colors.textMuted)
                    Slider(
                        value = bgScrim,
                        onValueChange = onBgScrimChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = selected,
                            activeTrackColor = selected,
                            inactiveTrackColor = colors.divider
                        )
                    )
                }

                Spacer(Modifier.height(AppSpacing.sm))
                Box(
                    modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider)
                )
                Spacer(Modifier.height(AppSpacing.sm))

                Text(text = stringResource(R.string.player_opacity), color = colors.textMuted)
                Slider(
                    value = playerOpacity,
                    onValueChange = onPlayerOpacityChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = selected,
                        activeTrackColor = selected,
                        inactiveTrackColor = colors.divider
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                ControlLabel(text = stringResource(R.string.done), color = selected)
            }
        }
    )
}

@Preview
@Composable
private fun AccentPickerDialogPreview() {
    AccentPickerDialog(
        current = Color(0xFFFFB14A),
        onAccentChange = {},
        onDismiss = {}
    )
}
