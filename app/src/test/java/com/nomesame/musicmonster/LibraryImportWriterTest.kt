package com.nomesame.musicmonster

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.LibraryImportWriter
import com.nomesame.musicmonster.data.WriteResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

// The method names and Bundle keys of the SAF create/delete call protocol live
// in `DocumentsContract` as `@hide` (plus `@UnsupportedAppUsage`) constants, so
// the public SDK stubs hide them from the test compiler. They are defined here
// with their real wire values — verified against AOSP android-14.0.0_r1
// `DocumentsContract.java` and against the `android-all` jars Robolectric runs
// (`createDocument`/`deleteDocument` inline exactly these keys). The display
// name and MIME type keys are the *public* `Document.COLUMN_*` constants the
// framework itself uses, so they are referenced instead of a local copy.
//
// `android.provider.extra.*` values are NOT these: that prefix belongs to the
// picker `Intent` extras (`EXTRA_INITIAL_URI`, `EXTRA_MIME_TYPES`, …), a
// different protocol. Using them here would make the fake provider throw on the
// very first create, i.e. the suite would be red — that is the proof that the
// literals below are the real ones and not a guess.
private const val METHOD_CREATE_DOCUMENT = "android:createDocument"
private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
private const val EXTRA_URI = "uri"
private const val EXTRA_RESULT = "result"

/**
 * Robolectric tests for [LibraryImportWriter] against an in-memory fake document
 * provider (registered via [ShadowContentResolver.registerProviderInternal], the
 * same pattern as `SongProviderBoundaryTest`). No user library or real device
 * storage is touched: the "tree" and the delivered source are both fixtures.
 *
 * The fake provider answers the real SAF call protocol, so the writer talks to
 * `DocumentsContract` and `DocumentFile` exactly as it would against a device
 * provider: the bare tree URI is never queried (only `getTreeDocumentId` reads
 * it), while `query` is reached with a *document* URI (flags probe in
 * `canWrite`) and with a *children* URI (`listFiles`/`findFile`). It refuses to
 * create a document whose name already exists, so an overwrite would fail
 * loudly instead of silently truncating an existing file.
 *
 * Limitation (honest, not papered over): `DocumentsContract.deleteDocument`
 * returns `true` unconditionally on every supported API level and never reads
 * `EXTRA_RESULT` (only `renameDocument` does, which the writer does not use).
 * The delete-result key is therefore kept for wire fidelity but a wrong value
 * could not turn this suite red on its own; all other literals are covered by
 * behaviour, not by assertion of the constant itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LibraryImportWriterTest {

    private lateinit var context: Context
    private lateinit var provider: FakeDocProvider
    private lateinit var writer: LibraryImportWriter

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = FakeDocProvider()
        provider.resolver = context.contentResolver
        provider.attachInfo(context, ProviderInfo().apply { authority = provider.authority })
        ShadowContentResolver.registerProviderInternal(provider.authority, provider)
        writer = LibraryImportWriter(context)
    }

    private fun source(bytes: ByteArray, failAfter: Int = -1): Uri {
        val uri = Uri.parse("content://source/delivered")
        val resolver = context.contentResolver
        shadowOf(resolver).registerInputStreamSupplier(uri) {
            if (failAfter < 0) ByteArrayInputStream(bytes) else FailingInputStream(bytes, failAfter)
        }
        return uri
    }

    private fun createdBytes(result: WriteResult.Success): ByteArray =
        provider.output.getValue(DocumentsContract.getDocumentId(result.uri)).toByteArray()

    @Test fun successWritesByteIdenticalContentAndReportsName() {
        val payload = "ID3-fake-mp3-payload".toByteArray()
        val result = writer.write(provider.treeUri(), source(payload), "Song.mp3", "audio/mpeg")

        assertTrue(result is WriteResult.Success)
        val success = result as WriteResult.Success
        assertEquals("Song.mp3", success.displayName)
        assertArrayEquals(payload, createdBytes(success))
        assertEquals(1, provider.createCalls)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun takenNameGetsSuffixAndExistingFileIsNotOverwritten() {
        val existingId = provider.seed("Song.mp3")
        val payload = "second".toByteArray()
        val result = writer.write(provider.treeUri(), source(payload), "Song.mp3", "audio/mpeg")

        assertTrue(result is WriteResult.Success)
        val success = result as WriteResult.Success
        assertEquals("Song (2).mp3", success.displayName)
        assertEquals(payload.toList(), createdBytes(success).toList())
        // Exactly one new document; the seeded one is untouched and never got a stream.
        assertEquals(1, provider.createCalls)
        assertNull(provider.output[existingId])
    }

    @Test fun failureMidCopyLeavesNoPartialFileBehind() {
        val result = writer.write(
            provider.treeUri(),
            source(ByteArray(100) { 7 }, failAfter = 10),
            "Song.mp3",
            "audio/mpeg",
        )

        assertTrue(result is WriteResult.Failure)
        assertEquals(
            WriteResult.Reason.SOURCE_UNREADABLE,
            (result as WriteResult.Failure).reason,
        )
        assertEquals(1, provider.deleteCalls)
        assertEquals(1, provider.deleted.size)
        assertTrue(provider.children.getValue(FakeDocProvider.ROOT).isEmpty())
    }

    @Test fun noWritePermissionNeitherCreatesNorDeletes() {
        provider.writable = false
        val result = writer.write(provider.treeUri(), source("x".toByteArray()), "Song.mp3", "audio/mpeg")

        assertTrue(result is WriteResult.Failure)
        assertEquals(
            WriteResult.Reason.NO_WRITE_PERMISSION,
            (result as WriteResult.Failure).reason,
        )
        assertEquals(0, provider.createCalls)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun emptySourceFailsAndIsCleanedUp() {
        val result = writer.write(provider.treeUri(), source(ByteArray(0)), "Song.mp3", "audio/mpeg")

        assertTrue(result is WriteResult.Failure)
        assertEquals(WriteResult.Reason.EMPTY_CONTENT, (result as WriteResult.Failure).reason)
        assertEquals(1, provider.createCalls)
        assertEquals(1, provider.deleteCalls)
    }

    @Test fun emptyMimeTypeFallsBackToAudioMpeg() {
        val result = writer.write(provider.treeUri(), source("x".toByteArray()), "Song.mp3", "")
        assertTrue(result is WriteResult.Success)
        val id = DocumentsContract.getDocumentId((result as WriteResult.Success).uri)
        assertEquals("audio/mpeg", provider.docs.getValue(id).mime)
    }

    @Test fun untrustedNameIsSanitizedBeforeCreation() {
        val payload = "payload".toByteArray()
        val result = writer.write(
            provider.treeUri(),
            source(payload),
            "a/b\\c:d\u0001e",
            "audio/mpeg",
        )

        assertTrue(result is WriteResult.Success)
        val success = result as WriteResult.Success
        assertEquals("abcde", success.displayName)
        assertFalse(success.displayName.contains('/'))
        assertEquals(payload.toList(), createdBytes(success).toList())
    }

    @Test fun blankNameUsesFixedPlaceholder() {
        val result = writer.write(provider.treeUri(), source("x".toByteArray()), "   ", "audio/mpeg")
        assertTrue(result is WriteResult.Success)
        assertEquals("musicmonster_import", (result as WriteResult.Success).displayName)
    }

    private class FailingInputStream(
        private val data: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int {
            if (pos >= failAfter) throw IOException("source died mid-copy")
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= failAfter) throw IOException("source died mid-copy")
            if (pos >= data.size) return -1
            val n = minOf(len, failAfter - pos, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /**
     * Minimal in-memory `DocumentsProvider` surface: a single writable root,
     * child listing, `METHOD_CREATE_DOCUMENT` (into a byte sink) and
     * `METHOD_DELETE_DOCUMENT`. It refuses to create a document whose name
     * already exists, so an overwrite would surface as a loud failure.
     */
    private class FakeDocProvider : ContentProvider() {

        data class Doc(val id: String, val name: String, val mime: String, val isDir: Boolean)

        val authority = "imp.tree"
        lateinit var resolver: ContentResolver
        var writable = true
        val docs = linkedMapOf<String, Doc>()
        val children = linkedMapOf<String, MutableList<String>>()
        val output = linkedMapOf<String, ByteArrayOutputStream>()
        val deleted = mutableListOf<String>()
        var createCalls = 0
        var deleteCalls = 0
        private var seq = 0

        init {
            docs[ROOT] = Doc(ROOT, "root", DocumentsContract.Document.MIME_TYPE_DIR, true)
            children[ROOT] = mutableListOf()
        }

        fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(authority, ROOT)

        fun seed(name: String): String {
            val id = "seed${++seq}"
            docs[id] = Doc(id, name, "audio/mpeg", false)
            children.getValue(ROOT).add(id)
            return id
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val requested = projection ?: DEFAULT_COLUMNS
            val isChildren = uri.pathSegments.contains("children")
            val parentId = DocumentsContract.getDocumentId(uri)
            val ids = if (isChildren) {
                children[parentId].orEmpty()
            } else {
                listOfNotNull(parentId.takeIf { docs.containsKey(it) })
            }
            return MatrixCursor(requested).apply {
                for (id in ids) {
                    val values = rowFor(docs.getValue(id))
                    addRow(requested.map { values[it] }.toTypedArray())
                }
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            when (method) {
                METHOD_CREATE_DOCUMENT -> create(extras)
                METHOD_DELETE_DOCUMENT -> remove(extras)
                else -> super.call(method, arg, extras)
            }

        // The generic getParcelable(String) overload is deprecated on API 33+ but
        // remains the only one that works on the whole minSdk 24..compileSdk 36
        // range this test runs under, so it is used deliberately.
        @Suppress("DEPRECATION")
        private fun create(extras: Bundle?): Bundle {
            createCalls++
            // The framework writes the display name / MIME type under its own
            // public Document.COLUMN_* keys; there is no separate EXTRA_* here.
            val displayName = requireNotNull(extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val parentUri = extras?.getParcelable<Uri>(EXTRA_URI)
            val parentId = parentUri?.let { DocumentsContract.getDocumentId(it) } ?: ROOT
            // An overwrite is a bug in the writer, not something to paper over.
            check(children[parentId].orEmpty().none { docs[it]?.name == displayName }) {
                "refusing to overwrite existing document '$displayName'"
            }
            val id = "doc${++seq}"
            val mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                ?: "application/octet-stream"
            docs[id] = Doc(id, displayName, mime, false)
            children.getOrPut(parentId) { mutableListOf() }.add(id)
            val sink = ByteArrayOutputStream()
            output[id] = sink
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri(), id)
            shadowOf(resolver).registerOutputStream(uri, sink)
            return Bundle().apply { putParcelable(EXTRA_URI, uri) }
        }

        @Suppress("DEPRECATION")
        private fun remove(extras: Bundle?): Bundle {
            deleteCalls++
            val uri = extras?.getParcelable<Uri>(EXTRA_URI)
            uri?.let { DocumentsContract.getDocumentId(it) }?.let { id ->
                deleted.add(id)
                docs.remove(id)
                output.remove(id)
                children.values.forEach { it.remove(id) }
            }
            return Bundle().apply { putBoolean(EXTRA_RESULT, true) }
        }

        private fun rowFor(doc: Doc): Map<String, Any?> = mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to doc.id,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to doc.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to doc.mime,
            DocumentsContract.Document.COLUMN_FLAGS to flagsFor(doc),
            DocumentsContract.Document.COLUMN_SIZE to 0L,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to 0L,
        )

        private fun flagsFor(doc: Doc): Int = when {
            !writable -> 0
            doc.isDir -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            else -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }

        companion object {
            const val ROOT = "root"
            private val DEFAULT_COLUMNS = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        }
    }
}
