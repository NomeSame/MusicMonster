package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.ui.theme.LocalAppColors

/** Actions for the library selection; selection and playlist operations are hoisted. */
@Composable
fun SongSelectionBar(
    count: Int,
    allSelected: Boolean,
    hasPlaylists: Boolean,
    onAdd: () -> Unit,
    onCreate: () -> Unit,
    onToggleAll: () -> Unit,
    onClose: () -> Unit,
    targetPlaylistName: String? = null
) {
    val colors = LocalAppColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("song_selection_bar"),
        shape = RoundedCornerShape(16.dp),
        color = colors.panel.copy(alpha = 0.94f),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("selection_close")) {
                    Icon(Icons.Default.Close, stringResource(R.string.exit_selection), tint = colors.accent)
                }
                Column(Modifier.weight(1f)) {
                    if (targetPlaylistName != null) {
                        Text(targetPlaylistName, color = colors.textPrimary,
                            style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Text(pluralStringResource(R.plurals.selected_songs, count, count),
                        color = colors.textPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = onAdd, enabled = count > 0 && hasPlaylists,
                        modifier = Modifier.testTag("selection_add")) {
                        Text(if (targetPlaylistName == null) stringResource(R.string.add_to_playlist)
                            else stringResource(R.string.add_to_named_playlist, targetPlaylistName))
                    }
                    TextButton(onClick = onCreate, enabled = count > 0,
                        modifier = Modifier.testTag("selection_create")) {
                        Text(stringResource(R.string.create_playlist))
                    }
                }
                TextButton(onClick = onToggleAll, modifier = Modifier.testTag("selection_all")) {
                    Text(stringResource(if (allSelected) R.string.deselect_all else R.string.select_all))
                }
            }
        }
    }
}
