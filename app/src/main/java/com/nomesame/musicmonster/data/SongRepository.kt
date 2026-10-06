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
                            durationMs = durationMs.coerceAtLeast(0L)
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // No read permission (denied or revoked): treat as an empty
            // library so the app degrades gracefully instead of crashing.
            // The user can still grant access via the SAF folder picker.
            list.clear()
        } catch (_: Exception) {
            // Missing OEM columns, a dead provider or cursor failure must not
            // escape the ViewModel coroutine and crash the application.
            list.clear()
        }

        return MusicLogic.sortNatural(list.distinctBy { it.id })
    }

    private fun loadFromTree(treeUri: Uri): List<Song> {
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
            ?: return emptyList()
        val list = mutableListOf<Song>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        val visited = mutableSetOf<Uri>()
        while (stack.isNotEmpty() && !Thread.currentThread().isInterrupted) {
            val doc = stack.removeFirst()
            if (!visited.add(doc.uri)) continue
            try {
                if (doc.isDirectory) {
                    doc.listFiles().forEach { stack.add(it) }
                } else {
                    val name = doc.name ?: "Unknown"
                    if (MusicLogic.isAudioFile(name, doc.type)) {
                        list.add(Song(doc.uri.toString(), name.substringBeforeLast('.'), doc.uri, 0L))
                    }
                }
            } catch (_: Exception) {
                // One unreadable child does not discard the rest of the tree.
                // Visiting each URI once also bounds cyclic provider responses.
            }
        }
        return MusicLogic.sortNatural(list.distinctBy { it.id })
    }
}
