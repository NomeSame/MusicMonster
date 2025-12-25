package com.example.myapplication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.Song

/**
 * Displays a scrollable list of songs. Clicking an item triggers the supplied callback.
 */
@Composable
fun SongList(
    songs: List<Song>,
    onSongSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
): Unit {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(songs) { index, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clickable { onSongSelected(index) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(text = song.title, modifier = Modifier.weight(1f))
            }
        }
    }
}

