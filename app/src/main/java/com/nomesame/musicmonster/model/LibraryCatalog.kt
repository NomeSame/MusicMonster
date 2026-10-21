package com.nomesame.musicmonster.model

import com.nomesame.musicmonster.Song

/** Provider IDs are opaque; names and paths are display data, never parsed as IDs. */
data class LibraryFolder(val id: String, val parentId: String?, val name: String, val path: List<String>)

data class LibraryCatalog(
    val songs: List<Song> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val rootId: String? = null,
)

enum class LibraryPreset { VIDEOS_FIRST, AUDIO_FIRST, TITLE, VIDEOS, AUDIO;
    fun next() = entries[(ordinal + 1) % entries.size]
}

data class LibraryView(
    val songs: List<Song> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val preset: LibraryPreset = LibraryPreset.VIDEOS_FIRST,
    val browsing: Boolean = false,
    val folder: LibraryFolder? = null,
    val rootName: String? = null,
    val canBrowse: Boolean = false,
    val canGoBack: Boolean = false,
)
