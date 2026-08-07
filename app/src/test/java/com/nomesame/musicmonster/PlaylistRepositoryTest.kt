package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.PlaylistRepository
import com.nomesame.musicmonster.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Robolectric tests for playlist persistence and JSON import/export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 28, 30, 33, 34])
class PlaylistRepositoryTest {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var resolver: android.content.ContentResolver
    private lateinit var repo: PlaylistRepository

    private fun playlist(id: String, name: String, vararg songIds: String) =
        Playlist(id, name, mutableStateListOf(*songIds))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("pl_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        resolver = context.contentResolver
        repo = PlaylistRepository(prefs, resolver)
    }

    // --- save / load roundtrip -------------------------------------------

    @Test
    fun saveAndLoadRoundTrip() {
        val lists = listOf(
            playlist("playlist_0", "Rock", "a", "b"),
            playlist("playlist_1", "Chillout", "c")
        )
        repo.save(lists)
        val loaded = repo.load()
        assertNotNull(loaded)
        assertEquals(2, loaded!!.playlists.size)
        assertEquals("Rock", loaded.playlists[0].name)
        assertEquals(listOf("a", "b"), loaded.playlists[0].songIds.toList())
        assertEquals(2, loaded.nextSequence) // max numeric suffix + 1
    }

    @Test
    fun loadReturnsNullWhenNothingStored() {
        assertNull(repo.load())
    }

    @Test
    fun loadReturnsNullOnCorruptJson() {
        prefs.edit().putString("playlists_json", "this is {not json").apply()
        assertNull(repo.load())
    }

    @Test
    fun loadSkipsBlankIdOrNameEntries() {
        prefs.edit().putString(
            "playlists_json",
            """[{"id":"p1","name":"Good","songs":["x"]},{"id":"","name":"NoId"},{"id":"p2","name":"","songs":["y"]}]"""
        ).apply()
        val loaded = repo.load()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.playlists.size)
        assertEquals("Good", loaded.playlists[0].name)
    }

    @Test
    fun loadPreservesUnicodeAndSpecialCharacters() {
        val lists = listOf(playlist("playlist_0", "Tëst — Ünïcode 🎵", "s1"))
        repo.save(lists)
        val loaded = repo.load()
        assertEquals("Tëst — Ünïcode 🎵", loaded!!.playlists[0].name)
        assertEquals("s1", loaded.playlists[0].songIds[0])
    }

    @Test
    fun nextSequenceDerivedFromMaxNumericSuffix() {
        val lists = listOf(
            playlist("playlist_7", "Seven"),
            playlist("custom-id", "Custom", "a")
        )
        repo.save(lists)
        val loaded = repo.load()
        assertEquals(8, loaded!!.nextSequence)
    }

    // --- export ----------------------------------------------------------

    @Test
    fun exportWritesPrettyJsonToUri() {
        val (uri, out) = createDocUriWithOutput("export_test.json")
        repo.export(uri, listOf(playlist("playlist_3", "Export", "x", "y")))
        val text = out.toString("UTF-8")
        assertTrue(text.contains("\"Export\""))
        assertTrue(text.contains("\"x\""))
        assertTrue(text.contains("\n")) // pretty-printed with indent
    }

    // --- importInto ------------------------------------------------------

    @Test
    fun importIntoAddsNewPlaylistsAndAllocatesIds() {
        val target = mutableStateListOf(playlist("playlist_0", "Existing", "a"))
        writeDoc("import_test.json", """[{"id":"","name":"New","songs":["n1","n1","n2"]}]""")
        val next = repo.importInto(docUri("import_test.json"), target, 1)
        assertNotNull(next)
        assertEquals(2, target.size)
        assertEquals("New", target[1].name)
        // duplicate song ids are de-duplicated on import
        assertEquals(listOf("n1", "n2"), target[1].songIds.toList())
        // blank id -> allocated from currentSequence
        assertEquals("playlist_1", target[1].id)
        assertEquals(2, next!!)
    }

    @Test
    fun importIntoMergesByCaseInsensitiveName() {
        val target = mutableStateListOf(playlist("playlist_0", "Rock", "a"))
        writeDoc("merge_test.json", """[{"id":"other","name":"rock","songs":["b"]}]""")
        repo.importInto(docUri("merge_test.json"), target, 1)
        assertEquals(1, target.size)
        assertEquals(listOf("a", "b"), target[0].songIds.toList())
    }

    @Test
    fun importIntoMergesByIdWithoutDuplicateSongs() {
        val target = mutableStateListOf(playlist("p1", "Alpha", "a"))
        writeDoc("merge_by_id.json", """[{"id":"p1","name":"Alpha","songs":["a","b"]}]""")
        repo.importInto(docUri("merge_by_id.json"), target, 1)
        assertEquals(1, target.size)
        assertEquals(listOf("a", "b"), target[0].songIds.toList())
    }

    @Test
    fun importIntoReturnsNullOnInvalidJson() {
        val target = mutableStateListOf<Playlist>()
        writeDoc("bad.json", "{broken")
        assertNull(repo.importInto(docUri("bad.json"), target, 0))
        assertTrue(target.isEmpty())
    }

    @Test
    fun importIntoSkippedBlankNamesKeepSequence() {
        val target = mutableStateListOf<Playlist>()
        writeDoc("blank.json", """[{"id":"x","name":"","songs":["a"]}]""")
        val next = repo.importInto(docUri("blank.json"), target, 5)
        assertEquals(0, target.size)
        assertEquals(5, next)
    }

    @Test
    fun importIntoMissingUriReturnsNull() {
        val target = mutableStateListOf<Playlist>()
        assertNull(repo.importInto(Uri.parse("content://test/documents/nope"), target, 0))
    }

    @Test
    fun importIntoStaleUriDoesNotCrash() {
        // A revoked/stale SAF uri makes ContentResolver.openInputStream throw
        // (FileNotFoundException on device, UnsupportedOperationException in
        // Robolectric's shadow). importInto must swallow it and return null.
        val target = mutableStateListOf<Playlist>()
        shadowOf(resolver).setRegisterContentProviderException(
            Uri.parse("content://test/documents/revoked"),
            RuntimeException("no longer exists")
        )
        assertNull(repo.importInto(Uri.parse("content://test/documents/revoked"), target, 0))
    }

    @Test
    fun exportStaleUriDoesNotCrash() {
        // Same for export: a revoked output uri must not crash the app.
        shadowOf(resolver).setRegisterContentProviderException(
            Uri.parse("content://test/documents/revoked_out"),
            RuntimeException("no longer exists")
        )
        repo.export(
            Uri.parse("content://test/documents/revoked_out"),
            listOf(playlist("playlist_0", "Export", "x"))
        )
        // reaching here without throwing is the assertion
    }

    // --- helpers ---------------------------------------------------------

    /** Registers an output stream and returns the uri together with the stream
     *  so the caller can inspect what was written (Robolectric 4.12.2 offers no
     *  getter for registered streams). */
    private fun createDocUriWithOutput(name: String): Pair<Uri, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        val uri = Uri.parse("content://test/documents/$name")
        shadowOf(resolver).registerOutputStream(uri, out)
        return uri to out
    }

    private fun docUri(name: String): Uri = Uri.parse("content://test/documents/$name")

    private fun writeDoc(name: String, content: String) {
        shadowOf(resolver).registerInputStream(docUri(name), content.byteInputStream())
    }
}
