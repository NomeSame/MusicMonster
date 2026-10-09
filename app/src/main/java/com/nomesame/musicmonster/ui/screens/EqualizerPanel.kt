package com.nomesame.musicmonster.ui.screens

import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.equalizer),
                style = MaterialTheme.typography.titleMedium,
                color = textWarm,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
            Switch(
                modifier = Modifier.align(Alignment.CenterEnd),
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
                text = stringResource(R.string.audio_not_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = textMuted
            )
            return
        }

        Text(
            text = stringResource(R.string.presets_label, localizedPreset(presetLabel)),
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
                        Text(text = localizedPreset(label))
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
                text = stringResource(R.string.bass_boost),
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
                runCatching { bassBoost?.setStrength(value.toShort()) }
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = textMuted
            )
        )

        // Reading the effect can throw on OEM AudioFX implementations whose
        // session has gone away underneath us. Inside composition that would
        // take down the whole UI, so the panel degrades to "no bands" instead.
        val bandInfo = remember(equalizer) {
            runCatching {
                val count = equalizer.numberOfBands.toInt()
                val levelRange = equalizer.bandLevelRange
                Triple(count, levelRange[0].toInt(), levelRange[1].toInt())
            }.getOrNull()
        } ?: return@Column
        val (bandCount, minLevel, maxLevel) = bandInfo

        // Side effect, not composition work: this used to run on every
        // recomposition, and PlayerScreen recomposes once a second from the
        // position ticker — one AudioFX IPC per band per second while the
        // panel is open.
        LaunchedEffect(equalizer, bandCount) {
            runCatching {
                if (eqBandCount.value != bandCount || eqBandLevels.size != bandCount) {
                    eqBandLevels.clear()
                    repeat(bandCount) { bandIndex ->
                        eqBandLevels.add(equalizer.getBandLevel(bandIndex.toShort()).toInt())
                    }
                    eqBandCount.value = bandCount
                } else {
                    for (bandIndex in 0 until bandCount) {
                        equalizer.setBandLevel(
                            bandIndex.toShort(),
                            eqBandLevels[bandIndex].toShort()
                        )
                    }
                }
            }
        }

        repeat(bandCount) { bandIndex ->
            val band = bandIndex.toShort()
            val centerHz = eqBandHz.getOrNull(bandIndex)
                ?: runCatching { (equalizer.getCenterFreq(band) / 1000).toInt() }.getOrDefault(0)
            // The LaunchedEffect above fills eqBandLevels asynchronously, so on
            // the first frame it can still be shorter than bandCount.
            val level = eqBandLevels.getOrNull(bandIndex) ?: 0

            Text(
                text = stringResource(R.string.frequency_hz, centerHz),
                style = MaterialTheme.typography.labelMedium,
                color = textWarm,
                modifier = Modifier.padding(top = 6.dp)
            )
            Slider(
                value = level.toFloat(),
                valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                onValueChange = { newValue ->
                    val newLevel = newValue.toInt()
                    if (bandIndex < eqBandLevels.size) eqBandLevels[bandIndex] = newLevel
                    runCatching { equalizer.setBandLevel(band, newLevel.toShort()) }
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

@Composable
private fun localizedPreset(key: String): String = stringResource(when (key) {
    "Metal" -> R.string.preset_metal
    "Rock" -> R.string.preset_rock
    "Classic" -> R.string.preset_classic
    "Pop" -> R.string.preset_pop
    else -> R.string.preset_flat
})
