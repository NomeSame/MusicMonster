package com.nomesame.musicmonster.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/** Reads local media metadata off the UI thread; never prepares or changes the playback queue. */
class MediaDurationRepository private constructor(
    private val extract: (Uri) -> Long,
    private val cache: MediaDurationCache,
) {
    constructor(context: Context) : this(extractor(context), MediaDurationCache.shared(context))
    constructor(@Suppress("UNUSED_PARAMETER") context: Context, extract: (Uri) -> Long) : this(extract, MediaDurationCache())

    private companion object {
        fun extractor(context: Context): (Uri) -> Long = { uri ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context.applicationContext, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { retriever.release() }
        }
    }

    fun cached(uri: Uri, size: Long, modified: Long): Long = cache.get(uri, size, modified) ?: 0L
    fun flush() { runCatching { cache.flush() } } // A disposable cache must never fail a library load.

    /** Providers without a reliable modification stamp are re-read rather than kept stale. */
    fun read(uri: Uri, size: Long = 0, modified: Long = 0): Long {
        cache.get(uri, size, modified)?.let { return it }
        val duration = runCatching { extract(uri).coerceAtLeast(0L) }.getOrDefault(0L)
        cache.put(uri, size, modified, duration)
        return duration
    }
}
