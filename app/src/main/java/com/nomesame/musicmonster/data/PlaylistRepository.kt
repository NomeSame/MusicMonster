package com.nomesame.musicmonster.data

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.nomesame.musicmonster.model.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Persists playlists in SharedPreferences ("music_prefs" / key "playlists_json")
 * and handles JSON import/export. Behavior is identical to the original
 * MainActivity implementation; only the location changed.
 */
class PlaylistRepository(
    private val prefs: SharedPreferences,
    private val resolver: ContentResolver,
) {

    /** Result of [load]: parsed playlists plus the next free sequence number. */
    data class Loaded(val playlists: List<Playlist>, val nextSequence: Int)

    /** Parses persisted playlists, or null if none/invalid. */
    fun load(): Loaded? {
        val raw = prefs.stringOr("playlists_json", null) ?: return null
        val parsed = mutableListOf<Playlist>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isBlank() || name.isBlank()) continue
                val songsJson = obj.optJSONArray("songs") ?: JSONArray()
                val songIds = mutableStateListOf<String>()
                for (s in 0 until songsJson.length()) {
                    val songId = songsJson.optString(s)
                    if (songId.isNotBlank()) {
                        songIds.add(songId)
                    }
                }
                parsed.add(Playlist(id = id, name = name, songIds = songIds))
            }
        } catch (_: Throwable) {
            return null
        }
        val nextSequence = parsed.mapNotNull {
            it.id.removePrefix("playlist_").toIntOrNull()
        }.maxOrNull()?.plus(1) ?: 0
        return Loaded(parsed, nextSequence)
    }

    fun save(playlists: List<Playlist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            val songsArray = JSONArray()
            playlist.songIds.forEach { songsArray.put(it) }
            obj.put("songs", songsArray)
            array.put(obj)
        }
        prefs.edit().putString("playlists_json", array.toString()).apply()
    }

    fun export(uri: Uri, playlists: List<Playlist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            val songsArray = JSONArray()
            playlist.songIds.forEach { songsArray.put(it) }
            obj.put("songs", songsArray)
            array.put(obj)
        }
        try {
            resolver.openOutputStream(uri)?.use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(array.toString(2))
                }
            }
        } catch (_: Exception) {
            // Stale or revoked SAF uri (FileNotFoundException, SecurityException,
            // ...): a failed export must not crash the app. Nothing to roll back.
        }
    }

    /**
     * Reads [uri] as text, or null if it can't be read. Split out from
     * [importInto] so callers can do the (blocking) read on a background
     * thread and the snapshot-state merge on the main thread.
     */
    fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
    } catch (_: Exception) {
        // Stale or revoked SAF uri (FileNotFoundException, SecurityException,
        // ...): treat like "no readable file" -> null, caller shows nothing.
        null
    }

    /**
     * Merges playlists from [uri] into [target] in place (matching by id or
     * case-insensitive name), allocating ids from [currentSequence] as needed.
     * Returns the recomputed next sequence, or null if the file was invalid.
     */
    fun importInto(uri: Uri, target: SnapshotStateList<Playlist>, currentSequence: Int): Int? =
        readText(uri)?.let { importInto(it, target, currentSequence) }

    /** Same merge as above, on already-read JSON [raw]. */
    fun importInto(raw: String, target: SnapshotStateList<Playlist>, currentSequence: Int): Int? {
        var sequence = currentSequence
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (name.isBlank()) continue
                val songsJson = obj.optJSONArray("songs") ?: JSONArray()
                val songIds = mutableListOf<String>()
                for (s in 0 until songsJson.length()) {
                    val songId = songsJson.optString(s)
                    if (songId.isNotBlank()) {
                        songIds.add(songId)
                    }
                }
                val existing = target.firstOrNull { it.id == id || it.name.equals(name, true) }
                if (existing != null) {
                    // Hash the existing ids once instead of scanning the list
                    // per imported song: merging a large import into a large
                    // playlist was quadratic, and this runs on the UI thread
                    // because it mutates snapshot state.
                    val present = existing.songIds.toHashSet()
                    songIds.forEach { songId ->
                        if (present.add(songId)) {
                            existing.songIds.add(songId)
                        }
                    }
                } else {
                    val playlistId = if (id.isBlank()) "playlist_${sequence++}" else id
                    val merged = Playlist(
                        id = playlistId,
                        name = name,
                        songIds = mutableStateListOf<String>().apply { addAll(songIds.distinct()) }
                    )
                    target.add(merged)
                }
            }
        } catch (_: Throwable) {
            return null
        }
        return target.mapNotNull {
            it.id.removePrefix("playlist_").toIntOrNull()
        }.maxOrNull()?.plus(1) ?: sequence
    }
}
