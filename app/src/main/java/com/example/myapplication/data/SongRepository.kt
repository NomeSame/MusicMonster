package com.example.myapplication.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.myapplication.Song

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
        val treeUri = prefs.getString("library_tree_uri", null)?.let { Uri.parse(it) }
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

        return list
    }

    private fun loadFromTree(treeUri: Uri): List<Song> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val list = mutableListOf<Song>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val doc = stack.removeFirst()
            if (doc.isDirectory) {
                doc.listFiles().forEach { stack.add(it) }
            } else {
                val name = doc.name ?: "Unknown"
                val type = doc.type
                if (type?.startsWith("audio/") == true || name.endsWith(".mp3", true)
                    || name.endsWith(".m4a", true) || name.endsWith(".flac", true)
                    || name.endsWith(".wav", true) || name.endsWith(".ogg", true)
                ) {
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
        return list.sortedBy { it.title.lowercase() }
    }
}
