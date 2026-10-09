package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.model.Playlist
import com.nomesame.musicmonster.ui.theme.LocalAppColors

@Composable
fun SelectPlaylistDialog(playlists: List<Playlist>, onSelect: (Playlist) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(stringResource(R.string.add_to_playlist), color = colors.textPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    TextButton(onClick = { onSelect(playlist) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            ControlLabel(playlist.name, color = colors.textPrimary)
                            ControlLabel(pluralStringResource(R.plurals.song_count, playlist.songIds.size, playlist.songIds.size),
                                color = colors.textMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { ControlLabel(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun CreatePlaylistDialog(name: String, onNameChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(stringResource(R.string.create_playlist), color = colors.textPrimary) },
        text = {
            OutlinedTextField(value = name, onValueChange = { onNameChange(it.take(24)) },
                label = { ControlLabel(stringResource(R.string.playlist_name)) }, singleLine = true,
                modifier = Modifier.testTag("playlist_name"))
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank(), modifier = Modifier.testTag("playlist_confirm")) {
                ControlLabel(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { ControlLabel(stringResource(R.string.cancel)) } }
    )
}
