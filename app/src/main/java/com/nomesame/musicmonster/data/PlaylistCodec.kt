package com.nomesame.musicmonster.data

import org.json.JSONArray

/** Decodes untrusted playlist records and allocates collision-free local IDs. */
internal object PlaylistCodec {
    data class Record(val id: String, val name: String, val songs: List<String>)

    fun parse(raw: String): List<Record>? {
        val array = try { JSONArray(raw) } catch (_: Exception) { return null }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = when (val value = obj.opt("id")) {
                    null -> ""
                    is String -> value
                    else -> continue
                }
                val name = obj.opt("name") as? String ?: continue
                if (name.isBlank()) continue
                val songs = obj.optJSONArray("songs")
                val ids = buildList {
                    for (s in 0 until (songs?.length() ?: 0)) {
                        val value = songs?.opt(s) as? String ?: continue
                        if (value.isNotBlank()) add(value)
                    }
                }.distinct()
                add(Record(id, name, ids))
            }
        }
    }

    fun nextSequence(ids: Collection<String>, minimum: Int = 0): Int {
        val occupied = ids.toHashSet()
        val maximum = ids.mapNotNull {
            if (it.startsWith("playlist_")) {
                it.removePrefix("playlist_").toIntOrNull()?.takeIf { number -> number >= 0 }
            } else null
        }.maxOrNull()
        // At MAX_VALUE the next available gap is used, without wrapping negative.
        var candidate = if (maximum == Int.MAX_VALUE) minimum.coerceAtLeast(0)
            else maxOf(minimum.coerceAtLeast(0), maximum?.plus(1) ?: 0)
        while ("playlist_$candidate" in occupied) {
            candidate = if (candidate == Int.MAX_VALUE) 0 else candidate + 1
        }
        return candidate
    }
}
