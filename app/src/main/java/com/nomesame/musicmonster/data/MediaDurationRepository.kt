package com.nomesame.musicmonster.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache

/** Reads local media metadata off the UI thread; never prepares or changes the playback queue. */
class MediaDurationRepository(
    context: Context,
    private val extract: (Uri) -> Long = { uri ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context.applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { retriever.release() }
    },
) {
    private data class Key(val uri: Uri, val size: Long, val modified: Long)
    private val cache = LruCache<Key, Long>(2048)

    /** Providers without a reliable modification stamp are re-read rather than kept stale. */
    fun read(uri: Uri, size: Long = 0, modified: Long = 0): Long {
        val key = Key(uri, size, modified)
        val cacheable = size > 0 && modified > 0
        if (cacheable) cache.get(key)?.let { return it }
        val duration = runCatching { extract(uri).coerceAtLeast(0L) }.getOrDefault(0L)
        if (cacheable && duration > 0) cache.put(key, duration)
        return duration
    }
}
