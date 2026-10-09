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
        val records = PlaylistCodec.parse(raw) ?: return null
        val parsed = linkedMapOf<String, Playlist>()
        for (record in records) {
            if (record.id.isBlank()) continue
            val playlist = parsed.getOrPut(record.id) {
                Playlist(record.id, record.name, mutableStateListOf())
            }
            playlist.songIds.addAll(PlaylistCodec.missingOccurrences(playlist.songIds, record.songs))
        }
        return Loaded(parsed.values.toList(), PlaylistCodec.nextSequence(parsed.keys))
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
            resolver.openOutputStream(uri, "wt")?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
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
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
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
        // Parse the whole document before changing observable state. Malformed
        // records are skipped, while invalid document syntax changes nothing.
        val records = PlaylistCodec.parse(raw) ?: return null
        val reserved = (target.map { it.id } + records.map { it.id }).toMutableSet()
        var sequence = currentSequence.coerceAtLeast(0)
        for (record in records) {
            val existing = target.firstOrNull { record.id.isNotBlank() && it.id == record.id }
                ?: target.firstOrNull { it.name.equals(record.name, true) }
            if (existing != null) {
                existing.songIds.addAll(PlaylistCodec.missingOccurrences(existing.songIds, record.songs))
            } else {
                val id = record.id.takeIf { it.isNotBlank() } ?: run {
                    val available = PlaylistCodec.nextSequence(reserved, sequence)
                    sequence = if (available == Int.MAX_VALUE) 0 else available + 1
                    "playlist_$available"
                }
                reserved.add(id)
                target.add(Playlist(id, record.name,
                    mutableStateListOf<String>().apply { addAll(record.songs) }))
            }
        }
        return PlaylistCodec.nextSequence(target.map { it.id }, sequence)
    }
}
