package com.nomesame.musicmonster

/** Immutable selection rules; IDs survive library reordering without selecting other songs. */
data class SongSelection(val active: Boolean = false, val ids: Set<String> = emptySet(), val targetPlaylistId: String? = null) {
    fun start(id: String) = SongSelection(true, setOf(id))

    fun forPlaylist(id: String) = SongSelection(true, emptySet(), id)

    fun toggle(id: String): SongSelection =
        if (!active) this else copy(ids = if (id in ids) ids - id else ids + id)

    fun allSelected(available: Collection<String>): Boolean =
        available.isNotEmpty() && available.all { it in ids }

    fun toggleAll(available: Collection<String>): SongSelection =
        if (!active) this else copy(ids = if (allSelected(available)) emptySet() else available.toSet())

    fun retain(available: Collection<String>) = copy(ids = ids.intersect(available.toSet()))

    /** Use library order, never checkbox click order; ignore missing and duplicate IDs. */
    fun orderedIds(available: Collection<String>): List<String> = available.filter { it in ids }.distinct()
}
