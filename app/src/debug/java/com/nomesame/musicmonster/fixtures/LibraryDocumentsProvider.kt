package com.nomesame.musicmonster.fixtures

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** Debug-only, read-only nested trees in owned cache fixtures; no user files. */
class LibraryDocumentsProvider : DocumentsProvider() {
    // Android may expose cacheDir through /data/user/0 while canonical children
    // resolve through /data/data. Relative IDs need the same canonical base.
    private val root get() = File(context!!.cacheDir, "library-fixtures").canonicalFile.apply { mkdirs() }
    private fun file(id: String): File {
        val value = File(root, id).canonicalFile
        require(value.path.startsWith(root.canonicalPath + File.separator))
        return value
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: emptyArray())
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        onQuery?.invoke(false)
        return rows(listOf(file(documentId)), projection)
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        onQuery?.invoke(true)
        return rows(file(parentDocumentId).listFiles()?.toList().orEmpty(), projection)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        file(documentId).path.startsWith(file(parentDocumentId).path + File.separator)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r")
        beforeOpen?.invoke(documentId)
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun rows(files: List<File>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(columns).apply {
            files.filter { it.exists() }.forEach { f -> addRow(columns.map { when (it) {
                Document.COLUMN_DOCUMENT_ID -> f.relativeTo(root).invariantSeparatorsPath
                Document.COLUMN_DISPLAY_NAME -> f.name
                Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else when (f.extension) {
                    "mp4" -> "video/mp4"
                    "wav" -> "audio/wav"
                    else -> "application/octet-stream"
                }
                Document.COLUMN_SIZE -> f.length()
                Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                else -> 0
            } }.toTypedArray()) }
        }
    }
    companion object {
        @Volatile var onQuery: ((Boolean) -> Unit)? = null
        @Volatile var beforeOpen: ((String) -> Unit)? = null
    }
}
