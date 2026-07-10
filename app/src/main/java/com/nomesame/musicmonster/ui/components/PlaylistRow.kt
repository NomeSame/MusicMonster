package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A playlist entry in the playlists list: name, song count and a delete button.
 * Stateless; open/delete are hoisted to the caller.
 */
@Composable
fun PlaylistRow(
    name: String,
    songCount: Int,
    textWarm: Color,
    textMuted: Color,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onOpen() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = textWarm,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$songCount songs",
            style = MaterialTheme.typography.labelSmall,
            color = textMuted,
            modifier = Modifier.padding(end = 6.dp)
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete playlist",
                tint = textMuted
            )
        }
    }
}

@Preview
@Composable
private fun PlaylistRowPreview() {
    PlaylistRow(
        name = "Chill Vibes",
        songCount = 12,
        textWarm = Color.White,
        textMuted = Color.Gray,
        onOpen = {},
        onDelete = {}
    )
}
