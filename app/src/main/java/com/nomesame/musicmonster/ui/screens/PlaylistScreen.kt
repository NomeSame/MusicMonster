package com.nomesame.musicmonster.ui.screens

import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.ui.components.ControlLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.model.Playlist
import com.nomesame.musicmonster.ui.components.PlaylistRow
import com.nomesame.musicmonster.ui.components.PlaylistSongRow

@Composable
fun PlaylistScreen(
    songs: List<Song>,
    playlists: SnapshotStateList<Playlist>,
    textWarm: Color,
    textMuted: Color,
    accent: Color,
    onPlayPlaylist: (Playlist, String, Int) -> Unit,
    onSavePlaylists: () -> Unit,
    onCreatePlaylist: (String, Song?) -> Playlist,
    onAddSongs: (Playlist) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    var activePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var createPlaylistName by rememberSaveable { mutableStateOf("") }
    val activePlaylist = playlists.firstOrNull { it.id == activePlaylistId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        if (activePlaylist != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activePlaylistId = null }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = textWarm
                        )
                    }
                    Text(
                        text = activePlaylist.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = textWarm
                    )
                }

                Button(
                    onClick = { onAddSongs(activePlaylist) },
                    modifier = Modifier.padding(bottom = 8.dp).testTag("playlist_add_songs")
                ) {
                    ControlLabel(stringResource(R.string.add_songs))
                }
            }

            val songsById = songs.associateBy { it.id }
            val playlistSongs = activePlaylist.songIds.mapIndexedNotNull { index, id ->
                songsById[id]?.let { index to it }
            }

            if (playlistSongs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.playlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textMuted
                    )
                }
            } else {
                itemsIndexed(playlistSongs, key = { _, entry -> entry.first }) { _, entry ->
                    val (playlistIndex, song) = entry
                    PlaylistSongRow(
                        modifier = Modifier.testTag("playlist_song_" + playlistIndex),
                        title = song.title,
                        textWarm = textWarm,
                        textMuted = textMuted,
                        onPlay = { onPlayPlaylist(activePlaylist, song.id, playlistIndex) },
                        onRemove = {
                            activePlaylist.songIds.removeAt(playlistIndex)
                            onSavePlaylists()
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        } else {
            item {
                Text(
                    text = stringResource(R.string.playlists),
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                        ControlLabel(stringResource(R.string.export))
                    }
                    Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                        ControlLabel(stringResource(R.string.import_playlists))
                    }
                }
                Button(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    ControlLabel(stringResource(R.string.new_playlist))
                }
            }

            if (playlists.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PlaylistAdd,
                            contentDescription = null,
                            tint = textMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.playlist_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted
                        )
                    }
                }
            } else {
                itemsIndexed(playlists) { _, playlist ->
                    PlaylistRow(
                        modifier = Modifier.testTag("playlist_row_" + playlist.id),
                        name = playlist.name,
                        songCount = playlist.songIds.size,
                        textWarm = textWarm,
                        textMuted = textMuted,
                        onOpen = { activePlaylistId = playlist.id },
                        onDelete = {
                            playlists.remove(playlist)
                            onSavePlaylists()
                        }
                    )
                    Divider(color = textMuted.copy(alpha = 0.3f))
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.create_playlist),
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm
                )
            },
            text = {
                OutlinedTextField(
                    value = createPlaylistName,
                    onValueChange = { createPlaylistName = it.take(24) },
                    label = { ControlLabel(stringResource(R.string.playlist_name), color = textMuted) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = textWarm),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        focusedLabelColor = accent,
                        unfocusedBorderColor = textMuted,
                        unfocusedLabelColor = textMuted,
                        cursorColor = accent
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = createPlaylistName.trim()
                    if (name.isNotEmpty()) {
                        val created = onCreatePlaylist(name, null)
                        activePlaylistId = created.id
                        onAddSongs(created)
                    }
                    createPlaylistName = ""
                    showCreatePlaylistDialog = false
                }) {
                    ControlLabel(stringResource(R.string.create))
                }
            },
            dismissButton = {
                Button(onClick = {
                    createPlaylistName = ""
                    showCreatePlaylistDialog = false
                }) {
                    ControlLabel(stringResource(R.string.cancel))
                }
            }
        )
    }
}
