package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.LibraryOrganization
import com.nomesame.musicmonster.MusicLogic
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.model.LibraryView

@Composable
fun LibrarySongList(view: LibraryView, listState: LazyListState, currentId: String?,
    selecting: Boolean, selectedIds: Set<String>, existingCounts: Map<String, Int>,
    text: Color, muted: Color, accent: Color, onFolder: (String) -> Unit,
    onSong: (Song) -> Unit, onLongSong: (Song) -> Unit, scanning: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(view.folders, key = { "folder:${it.id}" }) { folder ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("library_folder_${folder.id}")
                    .clickable(enabled = !selecting) { onFolder(folder.id) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = accent)
                    Spacer(Modifier.width(10.dp))
                    Text(folder.name, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (view.songs.isEmpty()) item(key = "empty") {
                Text(stringResource(if (scanning) R.string.library_loading else R.string.library_empty), color = muted,
                    modifier = Modifier.padding(vertical = 24.dp).testTag("library_empty"))
            }
            items(view.songs, key = { "song:${it.id}" }) { song ->
                SongRow(song.title, MusicLogic.formatTrackDuration(song.durationMs), song.id == currentId,
                    text, muted, accent, { onSong(song) }, { onLongSong(song) },
                    selectionMode = selecting, isSelected = song.id in selectedIds,
                    existingCount = existingCounts[song.id] ?: 0,
                    modifier = Modifier.testTag("song_row_${song.id}"),
                    pathLabel = if (!view.browsing) song.folderPath.joinToString(" / ").takeIf { it.isNotBlank() } else null,
                    isVideo = LibraryOrganization.isVideo(song.fileName, song.mimeType))
                HorizontalDivider(color = muted.copy(alpha = 0.15f))
            }
        }
        FastScroller(listState, view.songs, Modifier.fillMaxSize(), accent, text, muted,
            leadingItemCount = view.folders.size)
    }
}
