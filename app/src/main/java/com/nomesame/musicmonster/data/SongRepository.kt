package com.nomesame.musicmonster.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import com.nomesame.musicmonster.MusicLogic
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.model.LibraryCatalog
import com.nomesame.musicmonster.model.LibraryLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Loads SAF/MediaStore catalogues without blocking first display on media parsing.
 * The explicit synchronous API still supports complete-metadata callers.
 */
class SongRepository(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val durations: MediaDurationRepository = MediaDurationRepository(context),
) {

    fun load(): List<Song> = loadCatalog().songs

    /** Fast path shared with playback: file metadata/cached durations, no media opens. */
    fun scanCatalog(cancellation: CancellationSignal? = null, partial: (LibraryCatalog) -> Unit = {}): LibraryCatalog {
        val loaded = loadFiles(cancellation, partial)
        cancellation?.throwIfCanceled()
        if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Library scan cancelled")
        SongReferenceRepository(prefs).recover(loaded.songs)
        return loaded
    }

    /** Synchronous compatibility API for explicit complete-metadata callers. */
    fun loadCatalog(): LibraryCatalog {
        val loaded = scanCatalog()
        return try { loaded.copy(songs = loaded.songs.map(::withDuration)) }
        finally { durations.flush() }
    }

    /** Names first, metadata afterwards, with bounded/conflated display updates. */
    fun observeCatalog() = channelFlow {
        val producer = this
        val loaded = coroutineScope {
            val cancellation = CancellationSignal()
            val queryCancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { cancellation.cancel() }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    scanCatalog(cancellation) { producer.trySend(LibraryLoad(it, scanning = true)) }
                }
            } finally { queryCancellation.cancel() }
        }
        send(LibraryLoad(loaded, scanning = false))
        runInterruptible(metadataIO) {
            val songs = loaded.songs.toMutableList()
            var changed = false
            var lastPublished = android.os.SystemClock.uptimeMillis()
            var lastFlushed = lastPublished
            try {
                for (index in songs.indices) {
                    producer.ensureActive()
                    if (Thread.currentThread().isInterrupted) break
                    val enriched = withDuration(songs[index])
                    if (enriched != songs[index]) { songs[index] = enriched; changed = true }
                    if (changed && android.os.SystemClock.uptimeMillis() - lastPublished >= 250) {
                        producer.trySend(LibraryLoad(loaded.copy(songs = songs.toList()), scanning = false))
                        if (android.os.SystemClock.uptimeMillis() - lastFlushed >= 3000) {
                            durations.flush()
                            lastFlushed = android.os.SystemClock.uptimeMillis()
                        }
                        changed = false
                        lastPublished = android.os.SystemClock.uptimeMillis()
                    }
                }
                producer.ensureActive()
                if (changed) producer.trySend(LibraryLoad(loaded.copy(songs = songs.toList()), scanning = false))
            } finally { durations.flush() }
        }
    }.buffer(Channel.CONFLATED)

    private fun withDuration(song: Song): Song = if (song.durationMs > 0) song else song.copy(
        durationMs = durations.read(song.uri, song.sizeBytes, song.modifiedMs))

    private companion object {
        // A slow native parser must not multiply workers on repeated folder changes.
        // Directory scans use a separate IO lane and remain free to deliver names.
        val metadataIO = Dispatchers.IO.limitedParallelism(1)
    }

    private fun loadFiles(cancellation: CancellationSignal?, partial: (LibraryCatalog) -> Unit): LibraryCatalog {
        val treeUri = runCatching {
            prefs.stringOr("library_tree_uri", null)?.let { Uri.parse(it) }
        }.getOrNull()
        if (treeUri != null) {
            return DocumentCatalogReader(context.contentResolver, durations).read(treeUri, cancellation, partial)
        }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val list = mutableListOf<Song>()

        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder, cancellation)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                var lastPartial = android.os.SystemClock.uptimeMillis()

                while (c.moveToNext()) {
                    val idLong = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "Unknown"
                    val fileName = if (nameCol >= 0) c.getString(nameCol) else null
                    val durationMs = c.getLong(durationCol)
                    val size = if (sizeCol >= 0) c.getLong(sizeCol).coerceAtLeast(0L) else 0L
                    val modified = if (modifiedCol >= 0) c.getLong(modifiedCol).coerceAtLeast(0L) * 1000L else 0L

                    val contentUri = ContentUris.withAppendedId(collection, idLong)

                    list.add(
                        Song(
                            id = idLong.toString(),
                            title = fileName?.substringBeforeLast('.') ?: title,
                            uri = contentUri,
                            durationMs = if (durationMs > 0) durationMs else durations.cached(contentUri, size, modified),
                            fileName = fileName,
                            sizeBytes = size, modifiedMs = modified,
                        )
                    )
                    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Library scan cancelled")
                    if (list.size % 64 == 0 && android.os.SystemClock.uptimeMillis() - lastPartial >= 100) {
                        partial(LibraryCatalog(MusicLogic.sortNatural(list.toList())))
                        lastPartial = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: SecurityException) {
            // No read permission (denied or revoked): treat as an empty
            // library so the app degrades gracefully instead of crashing.
            // The user can still grant access via the SAF folder picker.
            list.clear()
        } catch (_: Exception) {
            // Missing OEM columns, a dead provider or cursor failure must not
            // escape the ViewModel coroutine and crash the application.
            list.clear()
        }

        return LibraryCatalog(MusicLogic.sortNatural(list.distinctBy { it.id }))
    }

}
