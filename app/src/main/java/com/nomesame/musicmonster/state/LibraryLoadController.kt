package com.nomesame.musicmonster.state

import com.nomesame.musicmonster.model.LibraryCatalog
import com.nomesame.musicmonster.model.LibraryLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Latest refresh wins, including non-cooperative late provider results. */
class LibraryLoadController(private val scope: CoroutineScope,
    private val load: () -> Flow<LibraryLoad>,
    private val onCatalog: (LibraryCatalog, Boolean) -> Unit,
    private val onScanned: () -> Unit = {}) {
    private var job: Job? = null
    private var generation = 0L
    private var completedRoot: String? = null
    private var displayedRoot: String? = null
    private val mutable = MutableStateFlow(false)
    val scanning = mutable.asStateFlow()

    fun refresh() {
        val current = ++generation
        val preserveRoot = completedRoot?.takeIf { it == displayedRoot }
        job?.cancel()
        mutable.value = true
        job = scope.launch {
            var completed = false
            try {
                load().collect { update ->
                    if (current == generation) {
                        // During a refresh, incomplete folder lists must not kick the
                        // user out of a still-existing nested folder or clear its titles.
                        if (!(update.scanning && preserveRoot != null && update.catalog.rootId == preserveRoot)) {
                            onCatalog(update.catalog, !update.scanning && !completed)
                            displayedRoot = update.catalog.rootId
                        }
                        mutable.value = update.scanning
                        if (!update.scanning && !completed) {
                            completed = true; completedRoot = update.catalog.rootId; onScanned()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last readable snapshot if a provider dies. */ }
            finally { if (current == generation) mutable.value = false }
        }
    }
}
