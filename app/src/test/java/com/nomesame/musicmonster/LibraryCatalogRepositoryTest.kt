package com.nomesame.musicmonster

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.SongRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LibraryCatalogRepositoryTest {
    private val authority = "owned.library.fixture"
    private val directory = DocumentsContract.Document.MIME_TYPE_DIR
    private lateinit var context: Context
    private lateinit var repo: SongRepository
    private var broken: String? = null
    private val nodes get() = mapOf(
        "root" to Triple("Music", directory, listOf("band", "empty", "audio", "root")),
        "band" to Triple("Heilung", directory, listOf("nested", "video", "other", "video")),
        "nested" to Triple("Album", directory, listOf("deep")),
        "empty" to Triple("Empty", directory, emptyList()),
        "audio" to Triple("Same.mp3", "audio/mpeg", emptyList()),
        "other" to Triple("Same.mp3", "audio/mpeg", emptyList()),
        "video" to Triple("opaque.bin", "video/mp4", emptyList()),
        "deep" to Triple("Song.flac", "audio/flac", emptyList()))
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("owned-library-repo", 0)
        prefs.edit().clear().putString("library_tree_uri", "content://$authority/tree/root").commit()
        repo = SongRepository(context, prefs)
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                val id = DocumentsContract.getDocumentId(uri)
                if (id == broken) throw SecurityException("owned fixture unavailable")
                val ids = if (uri.lastPathSegment == "children") nodes.getValue(id).third else listOf(id)
                val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type", "flags", "_size", "last_modified")
                return MatrixCursor(columns).apply {
                    ids.forEach { child ->
                        val node = nodes.getValue(child)
                        addRow(columns.map { when (it) {
                            "document_id" -> child
                            "_display_name" -> node.first
                            "mime_type" -> node.second
                            else -> 0
                        } }.toTypedArray())
                    }
                }
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = this@LibraryCatalogRepositoryTest.authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    @Test fun recursivelyKeepsEmptyFoldersOpaqueIdsDuplicateNamesAndVideoMime() {
        val c = repo.loadCatalog()
        assertEquals(4, c.songs.size)
        assertEquals(4, c.folders.size)
        assertEquals(2, c.songs.count { it.fileName == "Same.mp3" })
        assertEquals(4, c.songs.map { it.id }.distinct().size)
        assertEquals(listOf("Music", "Heilung", "Album"), c.songs.single { it.fileName == "Song.flac" }.folderPath)
        val video = c.songs.single { it.fileName == "opaque.bin" }
        assertEquals("video/mp4", video.mimeType)
        assertTrue(LibraryOrganization.isVideo(video.fileName, video.mimeType))
        val folder = c.folders.single { it.name == "Heilung" }
        assertEquals(folder.id, video.parentUri.toString())
        assertEquals(c.rootId, folder.parentId)
        assertTrue(c.folders.any { it.name == "Empty" })
    }
    @Test fun unreadableDirectoryDoesNotDiscardRootOrHealthySiblings() {
        broken = "band"
        val c = repo.loadCatalog()
        assertEquals(listOf("Same.mp3"), c.songs.map { it.fileName })
        assertTrue(c.folders.any { it.name == "Empty" })
    }
}
