package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.PlaylistRepository
import com.nomesame.musicmonster.model.Playlist
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real ContentResolver file streams, not registered Robolectric streams. */
@RunWith(AndroidJUnit4::class)
class PlaylistStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repo: PlaylistRepository
    private lateinit var file: File
    @Before fun setup() {
        prefs = context.getSharedPreferences("storage_probe", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repo = PlaylistRepository(prefs, context.contentResolver)
        file = File.createTempFile("playlist-probe-", ".json", context.cacheDir)
    }
    @After fun teardown() {
        if (::file.isInitialized) file.delete()
        if (::prefs.isInitialized) prefs.edit().clear().commit()
    }
    private fun playlist() = Playlist("p", "音楽 🎵", mutableStateListOf("α", "β"))
    @Test fun exportedFileIsUtf8AndRoundTripsThroughRealResolver() {
        repo.export(Uri.fromFile(file), listOf(playlist()))
        val target = mutableStateListOf<Playlist>()
        assertNotNull(repo.importInto(Uri.fromFile(file), target, 0))
        assertEquals("音楽 🎵", target.single().name)
        assertEquals(listOf("α", "β"), target.single().songIds.toList())
    }
    @Test fun shorterExportTruncatesThePreviousDocument() {
        file.writeText("OLD-DOCUMENT".repeat(1000), Charsets.UTF_8)
        repo.export(Uri.fromFile(file), listOf(playlist()))
        val raw = file.readText(Charsets.UTF_8)
        assertFalse(raw.contains("OLD-DOCUMENT"))
        assertEquals(1, JSONArray(raw).length())
    }
    @Test fun malformedNeighborsDoNotDiscardRealStoredPlaylists() {
        prefs.edit().putString("playlists_json",
            """[{"id":"a","name":"First","songs":["x"]},null,{"id":"b","name":"Second"}]""").commit()
        val loaded = requireNotNull(repo.load())
        assertEquals(listOf("a", "b"), loaded.playlists.map { it.id })
        assertEquals(listOf("x"), loaded.playlists.first().songIds.toList())
    }
}
