package com.nomesame.musicmonster.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Sleep-timer controls (presets + manual h/m/s + start/cancel + remaining
 * label). Owns its own UI-local timer state; reaches out only through
 * [onStartTimer] / [onCancelTimer]. [formatRemaining] renders the remaining ms.
 */
@Composable
fun SleepTimerPanel(
    textWarm: Color,
    textMuted: Color,
    accent: Color,
    formatRemaining: (Long) -> String,
    onStartTimer: (durationMs: Long, fadeMs: Long) -> Unit,
    onCancelTimer: () -> Unit
) {
    var hoursText by rememberSaveable { mutableStateOf("") }
    var minutesText by rememberSaveable { mutableStateOf("") }
    var secondsText by rememberSaveable { mutableStateOf("") }
    var sleepTotalMs by rememberSaveable { mutableStateOf(0L) }
    var sleepRemainingMs by rememberSaveable { mutableStateOf(0L) }
    var sleepTargetElapsedMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var timerRunning by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sleepTargetElapsedMs, timerRunning) {
        if (sleepTargetElapsedMs == null || !timerRunning) return@LaunchedEffect
        while (timerRunning) {
            val remaining = (sleepTargetElapsedMs!! - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)
            sleepRemainingMs = remaining
            if (remaining == 0L) {
                timerRunning = false
                break
            }
            delay(1000L)
        }
    }

    Column {
        Text(
            text = "Sleep Timer",
            style = MaterialTheme.typography.titleMedium,
            color = textWarm,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(15L, 30L, 60L).forEach { minutes ->
                Button(onClick = {
                    val totalMs = minutes * 60_000L
                    sleepTotalMs = totalMs
                    sleepRemainingMs = totalMs
                    sleepTargetElapsedMs = SystemClock.elapsedRealtime() + totalMs
                    timerRunning = true
                    onStartTimer(totalMs, 10_000L)
                }) {
                    Text("${minutes}m")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                focusedLabelColor = accent,
                unfocusedBorderColor = textMuted,
                unfocusedLabelColor = textMuted,
                cursorColor = accent
            )
            OutlinedTextField(
                value = hoursText,
                onValueChange = { hoursText = it.filter(Char::isDigit).take(2) },
                label = { Text("Hours", color = textMuted) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = minutesText,
                onValueChange = { minutesText = it.filter(Char::isDigit).take(2) },
                label = { Text("Minutes", color = textMuted) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = secondsText,
                onValueChange = { secondsText = it.filter(Char::isDigit).take(2) },
                label = { Text("Seconds", color = textMuted) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = {
                val h = hoursText.toLongOrNull() ?: 0L
                val m = minutesText.toLongOrNull() ?: 0L
                val s = secondsText.toLongOrNull() ?: 0L
                val totalMs = (h * 3600 + m * 60 + s) * 1000L
                if (totalMs > 0L) {
                    sleepTotalMs = totalMs
                    sleepRemainingMs = totalMs
                    sleepTargetElapsedMs = SystemClock.elapsedRealtime() + totalMs
                    timerRunning = true
                    onStartTimer(totalMs, 10_000L)
                }
            }) {
                Text("Start")
            }
            Button(onClick = {
                onCancelTimer()
                timerRunning = false
                sleepTargetElapsedMs = null
                sleepRemainingMs = sleepTotalMs
            }) {
                Text("Cancel")
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (sleepTotalMs > 0L) {
            val remainingLabel = formatRemaining(sleepRemainingMs)
            Text(
                text = if (timerRunning) "Time left: $remainingLabel" else "Set time: $remainingLabel",
                style = MaterialTheme.typography.labelMedium,
                color = textMuted
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Divider(color = textMuted.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Preview
@Composable
private fun SleepTimerPanelPreview() {
    SleepTimerPanel(
        textWarm = Color.White,
        textMuted = Color.Gray,
        accent = Color(0xFF80DEEA),
        formatRemaining = { "0:00:00" },
        onStartTimer = { _, _ -> },
        onCancelTimer = {}
    )
}
