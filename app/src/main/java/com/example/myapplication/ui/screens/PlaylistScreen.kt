package com.example.myapplication.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.Song
import com.example.myapplication.model.Playlist
import com.example.myapplication.ui.components.PlaylistRow
import com.example.myapplication.ui.components.PlaylistSongRow

@Composable
fun PlaylistScreen(
    songs: List<Song>,
    playlists: SnapshotStateList<Playlist>,
    textWarm: Color,
    textMuted: Color,
    accent: Color,
    onPlayPlaylist: (Playlist, String) -> Unit,
    onSavePlaylists: () -> Unit,
    onCreatePlaylist: (String, Song?) -> Playlist,
    onAddSongToPlaylist: (Playlist, Song) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    var activePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddSongsDialog by rememberSaveable { mutableStateOf(false) }
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
                            contentDescription = "Back",
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
                    onClick = { showAddSongsDialog = true },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("Add songs")
                }
            }

            val playlistSongs = activePlaylist.songIds.mapNotNull { id ->
                songs.firstOrNull { it.id == id }
            }

            if (playlistSongs.isEmpty()) {
                item {
                    Text(
                        text = "No songs in this playlist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textMuted
                    )
                }
            } else {
                itemsIndexed(playlistSongs) { _, song ->
                    PlaylistSongRow(
                        title = song.title,
                        textWarm = textWarm,
                        textMuted = textMuted,
                        onPlay = { onPlayPlaylist(activePlaylist, song.id) },
                        onRemove = {
                            activePlaylist.songIds.remove(song.id)
                            onSavePlaylists()
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        } else {
            item {
                Text(
                    text = "Playlists",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                        Text("Export")
                    }
                    Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                        Text("Import")
                    }
                }
                Button(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("New playlist")
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
                            text = "Long-press a song to create a playlist",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted
                        )
                    }
                }
            } else {
                itemsIndexed(playlists) { _, playlist ->
                    PlaylistRow(
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

    if (showAddSongsDialog && activePlaylist != null) {
        val availableSongs = songs.filterNot { activePlaylist.songIds.contains(it.id) }
        AlertDialog(
            onDismissRequest = { showAddSongsDialog = false },
            title = {
                Text(
                    text = "Add to ${activePlaylist.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm
                )
            },
            text = {
                if (availableSongs.isEmpty()) {
                    Text(
                        text = "All songs already in playlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textMuted
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        itemsIndexed(availableSongs) { _, song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable {
                                        onAddSongToPlaylist(activePlaylist, song)
                                        showAddSongsDialog = false
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textWarm,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAddSongsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = {
                Text(
                    text = "Create Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    color = textWarm
                )
            },
            text = {
                OutlinedTextField(
                    value = createPlaylistName,
                    onValueChange = { createPlaylistName = it.take(24) },
                    label = { Text("Name", color = textMuted) },
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
                        showAddSongsDialog = true
                    }
                    createPlaylistName = ""
                    showCreatePlaylistDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                Button(onClick = {
                    createPlaylistName = ""
                    showCreatePlaylistDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}