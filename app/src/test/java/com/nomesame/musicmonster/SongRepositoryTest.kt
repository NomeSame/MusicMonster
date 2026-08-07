package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.SongRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the song-library loader. The most fragile surface here
 * is the safety invariant shared with MusicService: a revoked/expired access
 * grant must degrade to an empty library (or whatever was already collected)
 * instead of crashing the app. The MediaStore path and the SAF-tree path are
 * exercised with a thrown SecurityException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: SongRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        repo = SongRepository(
            context,
            context.getSharedPreferences("sr_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
    }

    @Test
    fun emptyLibraryWhenNoTreeAndNoRows() {
        // No "library_tree_uri" in prefs and no MediaStore rows -> empty list.
        assertEquals(emptyList<Song>(), repo.load())
    }

    @Test
    fun mediaStoreSecurityExceptionDegradesToEmptyLibrary() {
        // Simulate the grant being revoked between permission-check and query:
        // the resolver throws SecurityException; load() must return empty, not crash.
        val resolver = context.contentResolver
        shadowOf(resolver).setRegisterContentProviderException(
            Uri.parse("content://media/external/audio/media"),
            SecurityException("grant revoked")
        )
        assertEquals(emptyList<Song>(), repo.load())
    }

    @Test
    fun malformedStoredTreeUriDoesNotCrash() {
        // A corrupt persisted folder uri must not crash load() — SongRepository
        // routes it through DocumentFile.fromTreeUri which returns null.
        val prefs = context.getSharedPreferences("sr_baduri_${System.nanoTime()}", Context.MODE_PRIVATE)
        prefs.edit().putString("library_tree_uri", "not a valid uri for a tree://").apply()
        val r = SongRepository(context, prefs)
        // fromTreeUri returns null -> empty, no exception.
        assertEquals(emptyList<Song>(), r.load())
    }
}