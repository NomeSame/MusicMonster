package com.nomesame.musicmonster

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
class SongProviderBoundaryTest {
    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repo: SongRepository
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("provider_boundaries", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repo = SongRepository(context, prefs)
    }
    private fun register(authority: String, provider: ContentProvider) {
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun media(rows: List<Array<Any?>> = emptyList(), failure: RuntimeException? = null) {
        register("media", object : EmptyProvider() {
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                failure?.let { throw it }
                return MatrixCursor(arrayOf("_id", "title", "duration")).apply { rows.forEach { addRow(it) } }
            }
        })
    }
    @Test fun providerFailureDoesNotCrashLibraryLoader() {
        media(failure = IllegalStateException("provider died"))
        assertTrue(repo.load().isEmpty())
    }
    @Test fun negativeDurationCannotReachSongUi() {
        media(listOf(arrayOf(1L, "Track", -1L)))
        assertEquals(0L, repo.load().single().durationMs)
    }
    @Test fun duplicateProviderIdsAreOnlyListedOnce() {
        media(listOf(arrayOf(1L, "Track", 100L), arrayOf(1L, "Track", 100L)))
        assertEquals(1, repo.load().size)
    }
    @Test fun nullTitleAndUnsortedRowsStillProduceUsableSortedSongs() {
        media(listOf(arrayOf(2L, "Track 10", 100L), arrayOf(1L, "Track 2", 100L), arrayOf(3L, null, 0L)))
        assertEquals(listOf("Track 2", "Track 10", "Unknown"), repo.load().map { it.title })
    }
    private fun tree(children: List<String>, broken: String? = null) {
        val columns = arrayOf("document_id", "_display_name", "mime_type", "flags", "last_modified", "_size")
        var calls = 0
        register("boundary.tree", object : EmptyProvider() {
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                check(++calls < 60) { "cyclic provider exceeded bounded query budget" }
                val id = DocumentsContract.getDocumentId(uri)
                val ids = if (uri.lastPathSegment == "children") children else listOf(id)
                val requested = projection ?: columns
                return object : MatrixCursor(requested) {
                    override fun close() {
                        super.close()
                        if (id == broken) throw IllegalStateException("child cursor died on close")
                    }
                }.apply {
                    for (item in ids) {
                        val values = arrayOf<Any?>(item, "$item.wav",
                            if (item == "root") DocumentsContract.Document.MIME_TYPE_DIR else "audio/wav", 0, 0L, 0L)
                        addRow(requested.map { values[columns.indexOf(it)] }.toTypedArray())
                    }
                }
            }
        })
        prefs.edit().putString("library_tree_uri", "content://boundary.tree/tree/root").commit()
    }
    @Test fun cyclicTreeDoesNotDuplicateSongsOrLoop() {
        tree(listOf("root", "a", "a"))
        assertEquals(listOf("a.wav".substringBeforeLast('.')), repo.load().map { it.title })
    }
    @Test fun brokenChildDoesNotPreventReadingItsHealthySiblings() {
        tree(listOf("bad", "good"), broken = "bad")
        assertEquals(listOf("good"), repo.load().map { it.title })
    }
    private abstract class EmptyProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
