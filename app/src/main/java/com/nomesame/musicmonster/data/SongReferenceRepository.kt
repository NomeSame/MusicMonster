package com.nomesame.musicmonster.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import com.nomesame.musicmonster.Song

/** Commit playlist occurrences and the saved playback ID together after a provider URI changes. */
class SongReferenceRepository(private val prefs: SharedPreferences) {
    /** Shared with library scans in this process; recovery must not race a live provider edit. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)
    /** Record intent before touching a provider, so process death cannot silently lose URI changes. */
    fun prepare(song: Song, target: String): Boolean {
        val raw = prefs.stringOr("playlists_json", null)
        if (raw != null && runCatching { JSONArray(raw) }.isFailure) return false
        val previous = pending()
        if (prefs.contains(PENDING) && previous == null) return false
        if (previous != null) return previous.optString("old") == song.id && previous.optString("target") == target
        val value = JSONObject().put("old", song.id).put("target", target)
            .put("parent", song.parentUri?.toString().orEmpty())
        return prefs.edit().putString(PENDING, value.toString()).commit()
    }

    fun abandon(oldId: String) {
        if (pending()?.optString("old") == oldId) prefs.edit().remove(PENDING).commit()
    }

    /** Recover a completed rename, or discard an intent whose original file still exists. */
    fun recover(songs: List<Song>) = exclusive {
        val pending = pending() ?: return@exclusive
        val old = pending.optString("old")
        val existing = songs.firstOrNull { it.id == old }
        val renamed = existing ?: songs.singleOrNull {
            it.fileName == pending.optString("target") && it.parentUri?.toString().orEmpty() == pending.optString("parent")
        }
        if (renamed != null) remap(old, renamed.id)
    }

    private fun pending(): JSONObject? = prefs.stringOr(PENDING, null)?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }

    fun remap(oldId: String, newId: String): Boolean = exclusive {
        val editor = prefs.edit()
        val raw = prefs.stringOr("playlists_json", null)
        if (raw != null) {
            val array = try { JSONArray(raw) } catch (_: Exception) { return@exclusive false }
            for (index in 0 until array.length()) {
                val songs = array.optJSONObject(index)?.optJSONArray("songs") ?: continue
                for (entry in 0 until songs.length()) if (songs.opt(entry) == oldId) songs.put(entry, newId)
            }
            editor.putString("playlists_json", array.toString())
        }
        if (prefs.stringOr("last_song_id", null) == oldId) editor.putString("last_song_id", newId)
        if (pending()?.optString("old") == oldId) editor.remove(PENDING)
        val committed = editor.commit()
        if (committed && oldId != newId) {
            val aliases = renamedIds.getOrPut(prefs) { mutableMapOf() }
            aliases.replaceAll { _, value -> if (value == oldId) newId else value }
            aliases.remove(newId)
            aliases[oldId] = newId
        }
        committed
    }

    /** Queued checkpoints can have captured the previous URI before a successful rename. */
    internal fun playbackId(id: String): String = exclusive { renamedIds[prefs]?.get(id) ?: id }

    private companion object {
        const val PENDING = "pending_file_rename"
        val lock = Any()
        // Only in-flight snapshots need aliases; process restart has no old checkpoint workers.
        val renamedIds = java.util.WeakHashMap<SharedPreferences, MutableMap<String, String>>()
    }
}
