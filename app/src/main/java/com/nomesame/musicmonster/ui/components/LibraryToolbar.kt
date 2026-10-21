package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.model.LibraryPreset
import com.nomesame.musicmonster.model.LibraryView

@Composable
private fun presetLabel(preset: LibraryPreset): String = stringResource(when (preset) {
    LibraryPreset.VIDEOS_FIRST -> R.string.library_videos_first
    LibraryPreset.AUDIO_FIRST -> R.string.library_audio_first
    LibraryPreset.TITLE -> R.string.library_title_order
    LibraryPreset.VIDEOS -> R.string.library_only_video
    LibraryPreset.AUDIO -> R.string.library_only_audio
})

/** Callbacks only: all navigation and ordering state belongs to LibraryController. */
@Composable
fun LibraryToolbar(view: LibraryView, accent: Color, text: Color, onPickFolder: () -> Unit,
    onBack: () -> Unit, onAll: () -> Unit, onToggleFolders: () -> Unit,
    onNextPreset: () -> Unit, onPreset: (LibraryPreset) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (view.canGoBack) IconButton(onClick = onBack, modifier = Modifier.testTag("library_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_back), tint = accent)
            }
            IconButton(onClick = onPickFolder, modifier = Modifier.testTag("folder_picker")) {
                Icon(Icons.Default.FolderOpen, stringResource(R.string.open_folder), tint = accent)
            }
            Text(view.folder?.path?.joinToString(" / ") ?: view.rootName ?: stringResource(R.string.music),
                Modifier.weight(1f).testTag("library_path"), color = text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onAll, modifier = Modifier.weight(1f).testTag("library_all")) {
                ControlLabel(stringResource(R.string.library_all), color = if (!view.browsing) accent else text)
            }
            TextButton(onClick = onToggleFolders, enabled = view.canBrowse,
                modifier = Modifier.weight(1f).testTag("library_folders")) {
                ControlLabel(stringResource(R.string.library_folders), color = if (view.browsing) accent else text)
            }
            Box(Modifier.weight(1.6f)) {
                TextButton(onClick = onNextPreset, modifier = Modifier.fillMaxWidth().testTag("library_preset")) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    ControlLabel(presetLabel(view.preset), color = accent)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("library_menu")) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.library_options), tint = accent)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_all)) },
                        onClick = { menu = false; onAll() })
                    LibraryPreset.entries.forEach { preset ->
                        DropdownMenuItem(text = { Text(presetLabel(preset)) },
                            onClick = { menu = false; onPreset(preset) })
                    }
                }
            }
        }
    }
}
