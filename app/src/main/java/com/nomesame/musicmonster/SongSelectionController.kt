package com.nomesame.musicmonster

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selection state and saved-state restoration, owned by MainViewModel. No persistence or UI. */
class SongSelectionController(private val savedState: SavedStateHandle) {
    private val mutableState = MutableStateFlow(
        SongSelection(savedState[ACTIVE] ?: false,
            (savedState.get<ArrayList<String>>(IDS) ?: arrayListOf()).toSet(), savedState[TARGET])
    )
    val state = mutableState.asStateFlow()

    fun start(id: String) = update(mutableState.value.start(id))
    fun forPlaylist(id: String) = update(mutableState.value.forPlaylist(id))
    fun toggle(id: String) = update(mutableState.value.toggle(id))
    fun toggleAll(available: Collection<String>) = update(mutableState.value.toggleAll(available))
    fun retain(available: Collection<String>) = update(mutableState.value.retain(available))
    fun finish() = update(SongSelection())

    private fun update(value: SongSelection) {
        savedState[ACTIVE] = value.active
        savedState[IDS] = ArrayList(value.ids)
        savedState[TARGET] = value.targetPlaylistId
        mutableState.value = value
    }

    private companion object {
        const val ACTIVE = "song_selection_active"
        const val IDS = "song_selection_ids"
        const val TARGET = "song_selection_target_playlist"
    }
}
