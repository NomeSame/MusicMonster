package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.CircularVisualizerView

/**
 * Stateless panel hosting the [CircularVisualizerView]. Fully hoisted: caller
 * supplies the audio session id and colors.
 */
@Composable
fun VisualizerPanel(
    audioSessionId: Int,
    textWarm: Color,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Visualizer",
            style = MaterialTheme.typography.titleMedium,
            color = textWarm,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        AndroidView(
            factory = { context ->
                CircularVisualizerView(context).apply {
                    setVisualizerColor(accent)
                }
            },
            update = { view ->
                view.setAudioSessionId(audioSessionId)
            },
            modifier = Modifier
                .size(220.dp)
                .padding(12.dp)
        )
    }
}

@Preview
@Composable
private fun VisualizerPanelPreview() {
    VisualizerPanel(
        audioSessionId = 0,
        textWarm = Color.White,
        accent = Color(0xFF80DEEA)
    )
}
