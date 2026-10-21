package com.nomesame.musicmonster.model

/** Partial scans are display-only; completed scans can reconcile selection and references. */
data class LibraryLoad(val catalog: LibraryCatalog, val scanning: Boolean)
