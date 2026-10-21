package com.nomesame.musicmonster.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

/** Bounded, disposable metadata cache. All disk access is on the library IO worker. */
class MediaDurationCache(private val file: File? = null) {
    private data class Value(val size: Long, val modified: Long, val duration: Long)
    private val values = object : LinkedHashMap<String, Value>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Value>?) = size > 4096
    }
    private var loaded = false
    private var dirty = false

    @Synchronized fun get(uri: Uri, size: Long, modified: Long): Long? {
        if (size <= 0 || modified <= 0) return null
        load()
        return values[uri.toString()]?.takeIf { it.size == size && it.modified == modified }?.duration
    }

    @Synchronized fun put(uri: Uri, size: Long, modified: Long, duration: Long) {
        if (size <= 0 || modified <= 0 || duration <= 0) return
        load()
        values[uri.toString()] = Value(size, modified, duration)
        dirty = true
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (file == null) return
        runCatching {
            AtomicFile(file).openRead().use { input ->
                val bytes = input.readBytesBounded()
                val rows = JSONArray(String(bytes, Charsets.UTF_8))
                require(rows.length() <= 4096)
                repeat(rows.length()) { index ->
                    val row = rows.getJSONArray(index)
                    val value = Value(row.getLong(1), row.getLong(2), row.getLong(3))
                    if (value.size > 0 && value.modified > 0 && value.duration > 0)
                        values[row.getString(0)] = value
                }
            }
        }.onFailure { values.clear() }
    }

    @Synchronized fun flush() {
        if (!dirty || file == null) return
        val atomic = AtomicFile(file)
        val rows = JSONArray()
        values.forEach { (uri, value) -> rows.put(JSONArray().put(uri).put(value.size).put(value.modified).put(value.duration)) }
        val output = atomic.startWrite()
        try {
            output.write(rows.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            dirty = false
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(result.size() + count <= 2 * 1024 * 1024)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    companion object {
        private val shared = mutableMapOf<String, MediaDurationCache>()
        @Synchronized fun shared(context: Context): MediaDurationCache {
            val file = File(context.cacheDir, "media-durations-v1.json")
            return shared.getOrPut(file.absolutePath) { MediaDurationCache(file) }
        }
    }
}
