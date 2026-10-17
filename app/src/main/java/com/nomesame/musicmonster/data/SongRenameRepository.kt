package com.nomesame.musicmonster.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.SongRenameRules

enum class RenameError { INVALID_NAME, NAME_EXISTS, READ_ONLY, MISSING, FAILED, REFERENCES }
sealed interface RenameOutcome {
    data class Success(val song: Song) : RenameOutcome
    data class Error(val reason: RenameError) : RenameOutcome
    data class Consent(val sender: IntentSender) : RenameOutcome
    object LegacyPermission : RenameOutcome
}

interface SongRenamer {
    fun name(song: Song): String?
    fun rename(song: Song, original: String, input: String, mayRequestPermission: Boolean): RenameOutcome
}

/** Changes the underlying file through the owning provider. Never creates/copies/deletes audio. */
class SongRenameRepository(private val context: Context) : SongRenamer {
    private val resolver get() = context.contentResolver

    override fun name(song: Song): String? = runCatching {
        resolver.query(song.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()

    override fun rename(song: Song, original: String, input: String, mayRequestPermission: Boolean): RenameOutcome {
        val target = SongRenameRules.fileName(input, original) ?: return RenameOutcome.Error(RenameError.INVALID_NAME)
        if (target == original) return RenameOutcome.Success(song.copy(fileName = original))
        val document = DocumentsContract.isDocumentUri(context, song.uri)
        if (!document && song.uri.authority != MediaStore.AUTHORITY) return RenameOutcome.Error(RenameError.READ_ONLY)
        var moved: Pair<java.io.File, java.io.File>? = null
        try {
            if (name(song) != original) return RenameOutcome.Error(RenameError.MISSING)
            if (!document && Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return if (mayRequestPermission) RenameOutcome.LegacyPermission
                else RenameOutcome.Error(RenameError.READ_ONLY)
            }
            if (hasCollision(song, target, document)) return RenameOutcome.Error(RenameError.NAME_EXISTS)
            val uri = if (document) {
                resolver.query(song.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                    if (!it.moveToFirst() || it.getLong(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() == 0L)
                        return RenameOutcome.Error(RenameError.READ_ONLY)
                } ?: return RenameOutcome.Error(RenameError.MISSING)
                DocumentsContract.renameDocument(resolver, song.uri, target)
                    ?: return RenameOutcome.Error(RenameError.FAILED)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, target)
                    if (Build.VERSION.SDK_INT <= 28) {
                        val path = resolver.query(song.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: return RenameOutcome.Error(RenameError.MISSING)
                        val oldFile = java.io.File(path)
                        val newFile = java.io.File(oldFile.parentFile, target)
                        if (newFile.exists()) return RenameOutcome.Error(RenameError.NAME_EXISTS)
                        if (!oldFile.renameTo(newFile)) return RenameOutcome.Error(RenameError.FAILED)
                        moved = oldFile to newFile
                        put(MediaStore.MediaColumns.DATA, newFile.absolutePath)
                    }
                    put(MediaStore.Audio.Media.TITLE, SongRenameRules.baseName(target))
                }
                if (resolver.update(song.uri, values, null, null) != 1) {
                    moved?.let { (old, new) -> new.renameTo(old) }
                    return RenameOutcome.Error(RenameError.FAILED)
                }
                moved = null
                song.uri
            }
            val actual = com.nomesame.musicmonster.SongRenameReadback.changedName(original,
                { name(song.copy(uri = uri)) }, { Thread.sleep(25) })
                ?: return RenameOutcome.Error(RenameError.FAILED)
            return RenameOutcome.Success(song.copy(
                id = if (document) uri.toString() else song.id,
                uri = uri, title = SongRenameRules.baseName(actual), fileName = actual,
            ))
        } catch (error: SecurityException) {
            moved?.let { (old, new) -> new.renameTo(old) }
            if (!document && mayRequestPermission) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val sender = runCatching { MediaStore.createWriteRequest(resolver, listOf(song.uri)).intentSender }.getOrNull()
                    if (sender != null) return RenameOutcome.Consent(sender)
                } else if (Build.VERSION.SDK_INT == 29 && error is RecoverableSecurityException) {
                    return RenameOutcome.Consent(error.userAction.actionIntent.intentSender)
                }
            }
            return RenameOutcome.Error(RenameError.READ_ONLY)
        } catch (_: Exception) {
            moved?.let { (old, new) -> new.renameTo(old) }
            return RenameOutcome.Error(RenameError.FAILED)
        }
    }

    private fun hasCollision(song: Song, target: String, document: Boolean): Boolean {
        if (document) {
            val parent = song.parentUri ?: throw IllegalStateException("Missing parent document")
            val siblings = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            resolver.query(siblings, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) != DocumentsContract.getDocumentId(song.uri) &&
                        cursor.getString(1).equals(target, ignoreCase = true)) return true
                }
            } ?: throw IllegalStateException("Cannot inspect siblings")
        } else {
            val directory = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
            val oldPath = resolver.query(song.uri, arrayOf(directory), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: throw IllegalStateException("Missing media directory")
            val parentPath = if (Build.VERSION.SDK_INT >= 29) oldPath else oldPath.substringBeforeLast('/')
            val collection = song.uri.buildUpon().path(song.uri.path!!.substringBeforeLast('/')).build()
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, directory),
                null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(2).orEmpty().let { if (Build.VERSION.SDK_INT >= 29) it else it.substringBeforeLast('/') }
                    if (cursor.getString(0) != song.id && path == parentPath && cursor.getString(1).equals(target, ignoreCase = true)) return true
                }
            } ?: throw IllegalStateException("Cannot inspect media directory")
        }
        return false
    }
}
