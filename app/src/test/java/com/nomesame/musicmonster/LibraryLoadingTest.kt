package com.nomesame.musicmonster

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.MediaDurationRepository
import com.nomesame.musicmonster.data.SongRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LibraryLoadingTest {
    private lateinit var context: Context
    private val queries = AtomicInteger()
    private val count = 2000
    private val authority = "owned.large.library"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
                MatrixCursor(requireNotNull(projection)).apply {
                    queries.incrementAndGet()
                    val children = uri.lastPathSegment == "children"
                    val ids = if (children) (0 until this@LibraryLoadingTest.count).map { "file-$it" } else listOf("root")
                    ids.forEach { id -> addRow(projection.map { column -> when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (children) "$id.mp3" else "Music"
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (children) "audio/mpeg" else DocumentsContract.Document.MIME_TYPE_DIR
                        DocumentsContract.Document.COLUMN_SIZE -> 64L
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1234L
                        else -> null
                    } }.toTypedArray()) }
                }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = this@LibraryLoadingTest.authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun repository(extract: (Uri) -> Long): SongRepository {
        val prefs = context.getSharedPreferences("owned-large-library", 0)
        prefs.edit().clear().putString("library_tree_uri", "content://$authority/tree/root").commit()
        return SongRepository(context, prefs, MediaDurationRepository(context, extract))
    }
    @Test fun twoQueriesLoadTwoThousandNamesWithoutOpeningAnyMedia() {
        val calls = AtomicInteger()
        val catalog = repository { calls.incrementAndGet(); 8000L }.scanCatalog()
        assertEquals(count, catalog.songs.size)
        assertEquals(2, queries.get())
        assertEquals(0, calls.get())
        assertTrue(catalog.songs.all { it.durationMs == 0L && it.folderPath == listOf("Music") })
    }
    @Test fun firstCompleteNameListIsDeliveredWhileTheFirstMetadataReadIsBlocked() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val names = CountDownLatch(1)
        val extracted = AtomicInteger()
        val repo = repository {
            entered.countDown()
            release.await()
            extracted.incrementAndGet()
            8000L
        }
        var latest = com.nomesame.musicmonster.model.LibraryCatalog()
        val job = launch(Dispatchers.Default) {
            repo.observeCatalog().collect { update ->
                latest = update.catalog
                if (!update.scanning && latest.songs.size == count) names.countDown()
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(names.await(10, TimeUnit.SECONDS))
            assertEquals(0, extracted.get())
            assertEquals(count, latest.songs.size)
        } finally { release.countDown() }
        withTimeout(10_000) { job.join() }
        assertTrue(latest.songs.all { it.durationMs == 8000L })
        assertEquals(2, queries.get()) // Enrichment reuses stamps from the folder cursor.
    }
    @Test fun cancellingAMetadataPassDoesNotStartTheRemainingFiles() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val repo = repository { calls.incrementAndGet(); entered.countDown(); release.await(); 8000L }
        val job = launch(Dispatchers.Default) { repo.observeCatalog().collect() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally { release.countDown() }
        withTimeout(10_000) { job.join() }
        assertEquals(1, calls.get())
    }
}
