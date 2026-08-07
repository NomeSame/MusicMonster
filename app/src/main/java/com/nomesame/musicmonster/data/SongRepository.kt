package com.nomesame.musicmonster.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.nomesame.musicmonster.MusicLogic
import com.nomesame.musicmonster.Song

/**
 * Loads the song library, either from a user-selected document tree
 * ("library_tree_uri" in prefs) or from MediaStore. Behavior is identical to
 * the original MainActivity implementation; only the location changed.
 */
class SongRepository(
    private val context: Context,
    private val prefs: SharedPreferences,
) {

    fun load(): List<Song> {
        val treeUri = runCatching {
            prefs.stringOr("library_tree_uri", null)?.let { Uri.parse(it) }
        }.getOrNull()
        if (treeUri != null) {
            return loadFromTree(treeUri)
        }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val list = mutableListOf<Song>()

        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val idLong = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "Unknown"
                    val durationMs = c.getLong(durationCol)

                    val contentUri = ContentUris.withAppendedId(collection, idLong)

                    list.add(
                        Song(
                            id = idLong.toString(),
                            title = title,
                            uri = contentUri,
                            durationMs = durationMs
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // No read permission (denied or revoked): treat as an empty
            // library so the app degrades gracefully instead of crashing.
            // The user can still grant access via the SAF folder picker.
            list.clear()
        } catch (_: IllegalArgumentException) {
            // getColumnIndexOrThrow: some vendor MediaStore providers omit
            // columns that are documented as always present. An unusable
            // provider must degrade to "no songs", not crash the app.
            list.clear()
        }

        return MusicLogic.sortNatural(list)
    }

    private fun loadFromTree(treeUri: Uri): List<Song> {
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
            ?: return emptyList()
        val list = mutableListOf<Song>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        try {
            while (stack.isNotEmpty()) {
                val doc = stack.removeFirst()
                if (doc.isDirectory) {
                    doc.listFiles().forEach { stack.add(it) }
                } else {
                    val name = doc.name ?: "Unknown"
                    val type = doc.type
                    if (MusicLogic.isAudioFile(name, type)) {
                        list.add(
                            Song(
                                id = doc.uri.toString(),
                                title = name.substringBeforeLast('.'),
                                uri = doc.uri,
                                durationMs = 0L
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // The SAF tree permission can be revoked at any time (user revokes
            // in Settings, or the grant expires) and third-party/cloud-backed
            // DocumentsProviders throw a grab-bag of RuntimeExceptions from
            // listFiles()/isDirectory when they are unhappy. Degrade to what we
            // already collected instead of crashing — same invariant as the
            // MediaStore path above.
        }
        return MusicLogic.sortNatural(list)
    }
}
