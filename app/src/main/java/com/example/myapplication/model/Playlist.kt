package com.example.myapplication.model

import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * A user-defined playlist. [songIds] is a Compose [SnapshotStateList] so that
 * add/remove operations recompose the UI observing it.
 */
class Playlist(
    val id: String,
    val name: String,
    val songIds: SnapshotStateList<String>
)
