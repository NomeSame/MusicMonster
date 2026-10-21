package com.nomesame.musicmonster.state

import androidx.lifecycle.SavedStateHandle
import com.nomesame.musicmonster.LibraryOrganization
import com.nomesame.musicmonster.data.LibrarySettingsRepository
import com.nomesame.musicmonster.model.LibraryCatalog
import com.nomesame.musicmonster.model.LibraryPreset
import com.nomesame.musicmonster.model.LibraryView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns presentation state only. Folder navigation cannot send playback commands. */
class LibraryController(private val settings: LibrarySettingsRepository, private val saved: SavedStateHandle) {
    private var catalog = LibraryCatalog()
    private var preset = settings.preset()
    private var browsing = settings.browsing()
    private var folderId: String? = saved["library_folder"]
    private var rootId: String? = saved["library_root"]
    private val mutable = MutableStateFlow(LibraryView(preset = preset))
    val view = mutable.asStateFlow()

    fun update(value: LibraryCatalog) {
        val metadataOnly = catalog.rootId == value.rootId && catalog.folders == value.folders &&
            catalog.songs.size == value.songs.size && catalog.songs.indices.all { index ->
                val old = catalog.songs[index]
                val new = value.songs[index]
                old.id == new.id && old.title == new.title && old.fileName == new.fileName &&
                    old.mimeType == new.mimeType && old.parentUri == new.parentUri && old.folderPath == new.folderPath
            }
        if (metadataOnly) {
            catalog = value
            val byId = value.songs.associateBy { it.id }
            mutable.value = mutable.value.copy(songs = mutable.value.songs.map { byId.getValue(it.id) })
            return // Duration updates cannot change ordering, navigation or preferences.
        }
        if (rootId != value.rootId) folderId = null
        rootId = value.rootId
        catalog = value
        refresh()
    }
    fun nextPreset() { preset = preset.next(); refresh() }
    fun setPreset(value: LibraryPreset) { preset = value; refresh() }
    fun showAll() {
        browsing = false
        if (preset == LibraryPreset.VIDEOS || preset == LibraryPreset.AUDIO) preset = LibraryPreset.VIDEOS_FIRST
        refresh()
    }
    fun toggleFolders() { browsing = !browsing; refresh() }
    fun open(id: String) {
        if (view.value.folders.none { it.id == id }) return
        folderId = id
        browsing = true
        refresh()
    }
    fun back() {
        if (!view.value.canGoBack) return
        folderId = view.value.folder?.parentId
        refresh()
    }
    private fun refresh() {
        val result = LibraryOrganization.present(catalog, preset, browsing, folderId)
        folderId = result.folder?.id ?: folderId
        saved["library_folder"] = folderId
        saved["library_root"] = rootId
        settings.save(preset, browsing)
        mutable.value = result
    }
}
