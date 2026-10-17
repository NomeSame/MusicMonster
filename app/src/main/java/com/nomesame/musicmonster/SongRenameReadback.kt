package com.nomesame.musicmonster

/** Providers may return stale metadata briefly after a successful mutation. */
object SongRenameReadback {
    fun changedName(original: String, read: () -> String?, pause: () -> Unit): String? {
        repeat(80) { attempt ->
            read()?.takeIf { it != original }?.let { return it }
            if (attempt < 79) pause()
        }
        return null // Never report success with the previous/unconfirmed filename.
    }
}
