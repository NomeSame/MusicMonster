package com.nomesame.musicmonster

import java.util.Locale
import kotlin.random.Random

/**
 * Pure, framework-independent music logic: natural title sorting, "next up"
 * selection, time formatting and shuffle-seed generation.
 *
 * Lives in the Business-Logik layer (see ARCHITECTURE.md) so it can be unit
 * tested on the JVM without any Android dependency. All UI/Service code routes
 * through these functions instead of re-implementing them.
 */
object MusicLogic {

    /** Compares two strings using natural ordering: "2" < "10", "a2" < "a10". */
    fun compareNatural(a: String, b: String): Int {
        // Locale.ROOT keeps case-folding identical on every device locale — the
        // Turkish "I"/"i" problem would otherwise make sorting differ per-locale.
        val la = a.lowercase(Locale.ROOT)
        val lb = b.lowercase(Locale.ROOT)
        var i = 0
        var j = 0
        while (i < la.length && j < lb.length) {
            if (la[i].isDigit() && lb[j].isDigit()) {
                // Skip leading zeros so "01" and "1" compare equal.
                while (i < la.length && Character.digit(la[i], 10) == 0) i++
                while (j < lb.length && Character.digit(lb[j], 10) == 0) j++
                // Compare digit runs as strings to avoid Long overflow on
                // extremely long runs (e.g. 30-digit filenames).
                val startA = i
                val startB = j
                while (i < la.length && la[i].isDigit()) i++
                while (j < lb.length && lb[j].isDigit()) j++
                val lenA = i - startA
                val lenB = j - startB
                if (lenA != lenB) return lenA.compareTo(lenB)
                for (k in 0 until lenA) {
                    val cA = Character.digit(la[startA + k], 10)
                    val cB = Character.digit(lb[startB + k], 10)
                    if (cA != cB) return cA.compareTo(cB)
                }
            } else {
                if (la[i] != lb[j]) return la[i].compareTo(lb[j])
                i++
                j++
            }
        }
        if (i < la.length) return 1
        if (j < lb.length) return -1
        return 0
    }

    /** Sorts a list of [Song] by natural title order (case-insensitive). */
    fun sortNatural(songs: List<Song>): List<Song> = songs.sortedWith { a, b ->
        compareNatural(a.title, b.title)
    }

    /**
     * Returns the song that should play next after [currentId] in [songs],
     * wrapping around. Falls back to the first song when the current song is
     * unknown (or null); returns null only for an empty list.
     */
    fun nextUpSong(songs: List<Song>, currentId: String?): Song? {
        if (songs.isEmpty()) return null
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        if (currentIndex < 0) return songs.first()
        return songs[(currentIndex + 1) % songs.size]
    }

    /**
     * Resolves playlist [ids] against the current library via [lookup] and
     * returns the resolvable entries plus the start index *within that result*.
     *
     * Extracted from MusicService because getting this wrong is not visible in
     * the happy path: when every id resolves, an index into [ids] and an index
     * into the resolved list are the same number. They diverge only once a
     * playlist references a song that has left the library — the normal state
     * after a rescan — and the stale index then lands outside the shorter list,
     * which ExoPlayer answers with IllegalSeekPositionException.
     *
     * The start index is 0 when [startId] itself is unresolvable, so playback
     * begins at the top rather than not at all.
     */
    fun <T> resolvePlaylist(
        ids: List<String>,
        startId: String?,
        lookup: (String) -> T?,
    ): Pair<List<T>, Int> {
        val resolved = ids.mapNotNull { id -> lookup(id)?.let { id to it } }
        val resolvedIds = resolved.map { it.first }
        val items = resolved.map { it.second }
        val startIndex = resolvedIds.indexOf(startId).takeIf { it >= 0 } ?: 0
        return items to startIndex
    }

    /** Formats a duration in ms as "M:SS" (or "H:MM:SS" past an hour). */
    fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Builds a shuffle seed. Uses a fresh random source instead of a
     * deterministic time-based value so consecutive shuffles are not near-
     * identical (previous behaviour produced near-identical orders when the
     * clock barely advanced between iterations).
     */
    fun buildShuffleSeed(): Long = Random.nextLong()

    /**
     * Returns true when a file (from its file name and optional MIME type)
     * should be treated as playable audio. Used by both the SAF tree loader
     * (SongRepository) and the service playlist builder (MusicService) so the
     * accepted formats stay consistent. MIME is consulted first; the name
     * extension is the fallback for providers that report no MIME type.
     */
    fun isAudioFile(name: String, mimeType: String?): Boolean =
        mimeType?.trim()?.startsWith("audio/", ignoreCase = true) == true ||
            name.endsWith(".mp3", true) ||
            name.endsWith(".m4a", true) ||
            name.endsWith(".flac", true) ||
            name.endsWith(".wav", true) ||
            name.endsWith(".ogg", true)
}
