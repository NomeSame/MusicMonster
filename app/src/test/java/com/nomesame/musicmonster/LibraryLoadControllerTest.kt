package com.nomesame.musicmonster

import com.nomesame.musicmonster.model.LibraryCatalog
import com.nomesame.musicmonster.model.LibraryLoad
import com.nomesame.musicmonster.state.LibraryLoadController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class LibraryLoadControllerTest {
    @Test fun refreshCancelsOldWorkAndOnlyCompletesSelectionReconciliationOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var starts = 0
        var cancellations = 0
        var completed = 0
        val updates = mutableListOf<Boolean>()
        val controller = LibraryLoadController(scope, {
            flow {
                starts++
                emit(LibraryLoad(LibraryCatalog(), scanning = true))
                if (starts == 1) {
                    try { awaitCancellation() } finally { cancellations++ }
                } else {
                    repeat(3) { emit(LibraryLoad(LibraryCatalog(), scanning = false)) }
                }
            }
        }, { _, complete -> updates.add(complete) }, { completed++ })
        try {
            controller.refresh()
            assertTrue(controller.scanning.value)
            controller.refresh()
            assertEquals(1, cancellations)
            assertEquals(listOf(false, false, true, false, false), updates)
            assertEquals(1, completed)
            assertFalse(controller.scanning.value)
        } finally { scope.cancel() }
    }
    @Test fun brokenProviderEndsTheSpinnerWithoutReplacingThePreviousView() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var updates = 0
        val controller = LibraryLoadController(scope, { flow { throw SecurityException("revoked") } }, { _, _ -> updates++ })
        try { controller.refresh(); assertFalse(controller.scanning.value); assertEquals(0, updates) }
        finally { scope.cancel() }
    }
    @Test fun refreshingTheSameRootKeepsExistingNestedFoldersUntilScanCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val folders = listOf(com.nomesame.musicmonster.model.LibraryFolder("band", "root", "Band", listOf("Music", "Band")))
        var shown = LibraryCatalog()
        val full = LibraryCatalog(folders = folders, rootId = "root")
        val controller = LibraryLoadController(scope, {
            flow {
                starts++
                if (starts > 1) { emit(LibraryLoad(LibraryCatalog(rootId = "root"), true)); release.await() }
                emit(LibraryLoad(full, false))
            }
        }, { catalog, _ -> shown = catalog })
        try {
            controller.refresh()
            controller.refresh()
            assertTrue(controller.scanning.value)
            assertEquals(folders, shown.folders)
            release.complete(Unit)
            assertFalse(controller.scanning.value)
            assertEquals(full, shown)
        } finally { scope.cancel() }
    }
}
