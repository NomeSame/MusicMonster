package com.nomesame.musicmonster.ui.screens

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Stateless equalizer + bass-boost panel. All state is hoisted: the caller
 * supplies the current [equalizer]/[bassBoost] effects, the observable band
 * state, and the [buildPresetLevels] pure function.
 *
 * [audioSessionId] is not read in the body; it is passed so that reading it at
 * the call site drives recomposition when the audio session (and therefore the
 * equalizer) becomes available.
 */
@Composable
fun EqualizerPanel(
    audioSessionId: Int,
    textWarm: Color,
    textMuted: Color,
    accent: Color,
    equalizer: Equalizer?,
    bassBoost: BassBoost?,
    eqEnabled: Boolean,
    onEqEnabledChanged: (Boolean) -> Unit,
    eqBandLevels: MutableList<Int>,
    eqBandCount: MutableState<Int>,
    eqBandHz: List<Int>,
    bassBoostEnabled: Boolean,
    onBassBoostEnabled: (Boolean) -> Unit,
    bassBoostStrength: Int,
    onBassBoostStrength: (Int) -> Unit,
    presetLabel: String,
    buildPresetLevels: (String, Equalizer) -> List<Int>,
    onPresetSelected: (String, List<Int>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Equalizer",
                style = MaterialTheme.typography.titleMedium,
                color = textWarm
            )
            Switch(
                checked = eqEnabled,
                onCheckedChange = {
                    onEqEnabledChanged(it)
                    equalizer?.enabled = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.5f),
                    uncheckedThumbColor = textMuted,
                    uncheckedTrackColor = textMuted.copy(alpha = 0.4f)
                )
            )
        }

        if (equalizer == null) {
            Text(
                text = "Audio session not ready",
                style = MaterialTheme.typography.bodyMedium,
                color = textMuted
            )
            return
        }

        Text(
            text = "Presets: $presetLabel",
            style = MaterialTheme.typography.labelMedium,
            color = textMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val presets = listOf("Metal", "Rock", "Classic", "Flat", "Pop")
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.take(3).forEach { label ->
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { onPresetSelected(label, buildPresetLevels(label, equalizer)) }
                    ) {
                        Text(text = label)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.drop(3).forEach { label ->
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { onPresetSelected(label, buildPresetLevels(label, equalizer)) }
                    ) {
                        Text(text = label)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Bass Boost",
                style = MaterialTheme.typography.titleSmall,
                color = textWarm
            )
            Switch(
                checked = bassBoostEnabled,
                onCheckedChange = {
                    onBassBoostEnabled(it)
                    bassBoost?.enabled = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.5f),
                    uncheckedThumbColor = textMuted,
                    uncheckedTrackColor = textMuted.copy(alpha = 0.4f)
                )
            )
        }
        Slider(
            value = bassBoostStrength.toFloat(),
            valueRange = 0f..1000f,
            onValueChange = { newValue ->
                val value = newValue.toInt()
                onBassBoostStrength(value)
                bassBoost?.setStrength(value.toShort())
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = textMuted
            )
        )

        val bandCount = equalizer.numberOfBands.toInt()
        val range = equalizer.bandLevelRange
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        if (eqBandCount.value != bandCount || eqBandLevels.size != bandCount) {
            eqBandLevels.clear()
            repeat(bandCount) { bandIndex ->
                val band = bandIndex.toShort()
                eqBandLevels.add(equalizer.getBandLevel(band).toInt())
            }
            eqBandCount.value = bandCount
        } else {
            for (bandIndex in 0 until bandCount) {
                val band = bandIndex.toShort()
                equalizer.setBandLevel(band, eqBandLevels[bandIndex].toShort())
            }
        }

        repeat(bandCount) { bandIndex ->
            val band = bandIndex.toShort()
            val centerHz = eqBandHz.getOrNull(bandIndex) ?: (equalizer.getCenterFreq(band) / 1000).toInt()
            val level = eqBandLevels[bandIndex]

            Text(
                text = "${centerHz} Hz",
                style = MaterialTheme.typography.labelMedium,
                color = textWarm,
                modifier = Modifier.padding(top = 6.dp)
            )
            Slider(
                value = level.toFloat(),
                valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                onValueChange = { newValue ->
                    val newLevel = newValue.toInt()
                    eqBandLevels[bandIndex] = newLevel
                    equalizer.setBandLevel(band, newLevel.toShort())
                },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = textMuted
                )
            )
        }
    }
}

@Preview
@Composable
private fun EqualizerPanelPreview() {
    EqualizerPanel(
        audioSessionId = 0,
        textWarm = Color.White,
        textMuted = Color.Gray,
        accent = Color(0xFF80DEEA),
        equalizer = null,
        bassBoost = null,
        eqEnabled = true,
        onEqEnabledChanged = {},
        eqBandLevels = remember { mutableStateListOf() },
        eqBandCount = remember { mutableStateOf(0) },
        eqBandHz = emptyList(),
        bassBoostEnabled = false,
        onBassBoostEnabled = {},
        bassBoostStrength = 600,
        onBassBoostStrength = {},
        presetLabel = "Flat",
        buildPresetLevels = { _, _ -> emptyList() },
        onPresetSelected = { _, _ -> }
    )
}
