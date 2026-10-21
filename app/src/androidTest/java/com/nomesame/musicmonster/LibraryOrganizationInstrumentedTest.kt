package com.nomesame.musicmonster

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.SongRepository
import com.nomesame.musicmonster.model.LibraryPreset
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryOrganizationInstrumentedTest {
    @Test fun realNestedDocumentTreeKeepsPathsEmptyFoldersAndDuplicateNames() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val id = "Music-${UUID.randomUUID()}"
        val root = File(context.cacheDir, "library-fixtures/$id").apply { check(mkdirs()) }
        val prefsName = "library-owned-$id"
        val prefs = context.getSharedPreferences(prefsName, 0)
        try {
            val band = File(root, "Heilung").apply { check(mkdir()) }
            assertTrue(File(band, "Empty").mkdir())
            File(root, "same.wav").writeText("unreadable owned audio")
            File(band, "same.wav").writeText("unreadable owned audio")
            inst.context.assets.open("owned-video.mp4").use { input -> File(band, "Live.mp4").outputStream().use(input::copyTo) }
            val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".library-fixture", id)
            // Check the real provider independently of the repository's intentional
            // unreadable-document fallback, so fixture faults have useful diagnostics.
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null).use { cursor ->
                assertNotNull("Owned fixture root must be queryable", cursor)
                assertTrue(cursor!!.moveToFirst())
                assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, cursor.getString(0))
            }
            val rootDocument = requireNotNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree))
            assertTrue("DocumentFile must recognize the fixture directory", rootDocument.isDirectory)
            assertEquals("DocumentFile must enumerate the root children", 2, rootDocument.listFiles().size)
            rootDocument.listFiles().forEach { child ->
                val childId = DocumentsContract.getDocumentId(child.uri)
                assertTrue("Child ID must stay relative to its own root: $childId", childId.startsWith("$id/"))
                assertTrue("Enumerated child must be readable: ${child.uri}", child.exists())
            }
            assertFalse("Repository worker must not already be interrupted", Thread.currentThread().isInterrupted)
            assertTrue(prefs.edit().putString("library_tree_uri", tree.toString()).commit())
            val catalog = SongRepository(context, prefs).loadCatalog()
            assertEquals("Folders discovered: ${catalog.folders}", 3, catalog.songs.size)
            assertEquals(3, catalog.folders.size)
            assertEquals(2, catalog.songs.count { it.fileName == "same.wav" })
            assertEquals(3, catalog.songs.map { it.id }.distinct().size)
            val folder = catalog.folders.single { it.name == "Heilung" }
            val bandView = LibraryOrganization.present(catalog, LibraryPreset.VIDEOS_FIRST, true, folder.id)
            assertEquals(listOf("Live.mp4", "same.wav"), bandView.songs.map { it.fileName })
            assertEquals(listOf("Empty"), bandView.folders.map { it.name })
            assertEquals(listOf(id, "Heilung"), bandView.songs.first().folderPath)
            assertTrue(bandView.songs.first().durationMs > 0)
            assertEquals(3, LibraryOrganization.present(catalog, LibraryPreset.TITLE, false, folder.id).songs.size)
            val rootView = LibraryOrganization.present(catalog, LibraryPreset.TITLE, true, catalog.rootId)
            assertEquals(listOf("same.wav"), rootView.songs.map { it.fileName })
        } finally {
            // Only the UUID-owned fixture, after checking its cache parent.
            assertEquals(File(context.cacheDir, "library-fixtures").canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.deleteRecursively())
            assertTrue(context.deleteSharedPreferences(prefsName))
        }
    }
}
