package com.nomesame.musicmonster.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.nomesame.musicmonster.extensions.ExtensionNames
import java.util.UUID

/** Generic SAF output staging. Calls run on IO; only the single child is delegated. */
class ExtensionDocumentRepository(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun create(tree: Uri): Uri {
        val folder = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        require(folder.isDirectory)
        // A provider owner can write its own documents without a separate URI grant;
        // DocumentFile.canWrite() incorrectly rejects that valid case. Check the advertised
        // capability and let createDocument enforce the real permission/provider operation.
        resolver.query(folder.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            check(it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
        } ?: error("Missing directory capabilities")
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, folder.uri,
            "application/octet-stream", ".extension-${UUID.randomUUID()}.partial"))
        try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                check(it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0)
            } ?: error("Missing document capabilities")
            return uri
        } catch (error: Exception) {
            discard(uri)
            throw error
        }
    }

    fun complete(tree: Uri, uri: Uri, name: String): Uri {
        require(ExtensionNames.valid(name))
        val folder = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        val child = requireNotNull(DocumentFile.fromSingleUri(context, uri))
        check(child.exists() && child.length() > 0)
        val chosen = ExtensionNames.available(name, folder.listFiles().mapNotNull { it.name }.toSet())
        val renamed = requireNotNull(DocumentsContract.renameDocument(resolver, uri, chosen))
        val actual = DocumentFile.fromSingleUri(context, renamed)?.name
        if (actual == null || !ExtensionNames.valid(actual)) {
            discard(renamed)
            error("Provider did not finalize the document")
        }
        return renamed
    }

    fun discard(uri: Uri): Boolean = try {
        val exists = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: return false
        !exists || DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: java.io.FileNotFoundException) { true }
    catch (_: Exception) { false }
}
