package com.nomesame.musicmonster

import com.nomesame.musicmonster.model.LibraryCatalog
import com.nomesame.musicmonster.model.LibraryPreset
import com.nomesame.musicmonster.model.LibraryView
import java.util.Locale

/** Pure classification and display projection. Never changes the playback catalogue. */
object LibraryOrganization {
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "3gp", "ts", "avi", "mov")

    fun isVideo(name: String?, mime: String?): Boolean {
        val type = mime?.trim()?.lowercase(Locale.ROOT)
        if (type?.startsWith("audio/") == true) return false
        return type?.startsWith("video/") == true ||
            name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in videoExtensions
    }

    fun accepts(name: String, mime: String?): Boolean =
        MusicLogic.isAudioFile(name, mime) || isVideo(name, mime)

    fun present(catalog: LibraryCatalog, preset: LibraryPreset, browsing: Boolean, folderId: String?): LibraryView {
        val root = catalog.folders.firstOrNull { it.id == catalog.rootId }
        val folder = if (browsing && root != null)
            catalog.folders.firstOrNull { it.id == folderId } ?: root else null
        val visible = catalog.songs.filter { song ->
            (folder == null || song.parentUri?.toString() == folder.id) && when (preset) {
                LibraryPreset.VIDEOS -> isVideo(song.fileName, song.mimeType)
                LibraryPreset.AUDIO -> !isVideo(song.fileName, song.mimeType)
                else -> true
            }
        }.sortedWith { a, b ->
            fun rank(song: Song): Int = when (preset) {
                LibraryPreset.VIDEOS_FIRST -> if (isVideo(song.fileName, song.mimeType)) 0 else 1
                LibraryPreset.AUDIO_FIRST -> if (isVideo(song.fileName, song.mimeType)) 1 else 0
                else -> 0
            }
            rank(a).compareTo(rank(b)).takeIf { it != 0 }
                ?: MusicLogic.compareNatural(a.title, b.title).takeIf { it != 0 }
                ?: a.id.compareTo(b.id)
        }
        val children = if (folder == null) emptyList() else catalog.folders
            .filter { it.parentId == folder.id }
            .sortedWith { a, b -> MusicLogic.compareNatural(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
        return LibraryView(visible, children, preset, folder != null, folder, root?.name,
            root != null, folder != null && folder.id != root?.id)
    }
}
