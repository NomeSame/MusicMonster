package com.nomesame.musicmonster.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract as Docs
import com.nomesame.musicmonster.LibraryOrganization
import com.nomesame.musicmonster.MusicLogic
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.model.LibraryCatalog
import com.nomesame.musicmonster.model.LibraryFolder
import kotlinx.coroutines.CancellationException

/** One projected cursor per folder, never a DocumentFile query per property/file. */
internal class DocumentCatalogReader(private val resolver: ContentResolver, private val durations: MediaDurationRepository) {
    private val projection = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
        Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED)

    fun read(tree: Uri, cancellation: CancellationSignal?, partial: (LibraryCatalog) -> Unit): LibraryCatalog {
        val songs = mutableListOf<Song>()
        val folders = mutableListOf<LibraryFolder>()
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<LibraryFolder>()
        val rootId = runCatching { Docs.getTreeDocumentId(tree) }.getOrNull() ?: return LibraryCatalog()
        val rootUri = Docs.buildDocumentUriUsingTree(tree, rootId)
        var lastPublished = 0L
        fun snapshot() = LibraryCatalog(MusicLogic.sortNatural(songs.toList()), folders.toList(), rootUri.toString())
        fun checkpoint(force: Boolean = false) {
            if (Thread.currentThread().isInterrupted || cancellation?.isCanceled == true)
                throw CancellationException("Library scan cancelled")
            val now = android.os.SystemClock.uptimeMillis()
            if (force || ((songs.size + folders.size) % 64 == 0 && now - lastPublished >= 100)) {
                partial(snapshot())
                lastPublished = now
            }
        }
        try {
            resolver.query(rootUri, projection, null, null, null, cancellation)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.string(Docs.Document.COLUMN_MIME_TYPE) == Docs.Document.MIME_TYPE_DIR) {
                    val name = cursor.string(Docs.Document.COLUMN_DISPLAY_NAME) ?: "Unknown"
                    val root = LibraryFolder(rootUri.toString(), null, name, listOf(name))
                    folders.add(root); pending.add(root); seen.add(rootId)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return LibraryCatalog(rootId = rootUri.toString()) }
        checkpoint(true)
        while (pending.isNotEmpty()) {
            checkpoint()
            val parent = pending.removeFirst()
            val parentUri = Uri.parse(parent.id)
            val children = Docs.buildChildDocumentsUriUsingTree(tree, Docs.getDocumentId(parentUri))
            try {
                resolver.query(children, projection, null, null, null, cancellation)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        checkpoint()
                        try {
                            val id = cursor.string(Docs.Document.COLUMN_DOCUMENT_ID) ?: continue
                            if (!seen.add(id)) continue
                            val uri = Docs.buildDocumentUriUsingTree(tree, id)
                            val name = cursor.string(Docs.Document.COLUMN_DISPLAY_NAME) ?: "Unknown"
                            val mime = cursor.string(Docs.Document.COLUMN_MIME_TYPE)
                            if (mime == Docs.Document.MIME_TYPE_DIR) {
                                val folder = LibraryFolder(uri.toString(), parent.id, name, parent.path + name)
                                folders.add(folder); pending.add(folder)
                            } else if (LibraryOrganization.accepts(name, mime)) {
                                val size = cursor.number(Docs.Document.COLUMN_SIZE)
                                val modified = cursor.number(Docs.Document.COLUMN_LAST_MODIFIED)
                                songs.add(Song(uri.toString(), name.substringBeforeLast('.'), uri,
                                    durations.cached(uri, size, modified), name, parentUri, mime, parent.path, size, modified))
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* One damaged metadata row must not hide its siblings. */ }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Preserve readable siblings and already collected rows. */ }
        }
        return snapshot()
    }

    private fun Cursor.string(column: String): String? = getColumnIndex(column).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.number(column: String): Long = getColumnIndex(column).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
}
