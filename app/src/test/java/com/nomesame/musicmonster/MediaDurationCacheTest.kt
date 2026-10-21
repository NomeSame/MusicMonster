package com.nomesame.musicmonster

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.nomesame.musicmonster.data.MediaDurationCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], shadows = [com.nomesame.musicmonster.fixtures.PosixAtomicFileShadow::class])
class MediaDurationCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://owned/audio")
    @Test fun survivesNewInstancesAndRequiresMatchingFileStamps() {
        val file = File.createTempFile("owned-duration-", ".json", context.cacheDir)
        try {
            val cache = MediaDurationCache(file)
            cache.put(uri, 64, 123, 8000)
            cache.flush()
            assertTrue("Persisted cache content: ${file.readText()}", file.readText().contains("8000"))
            val restored = MediaDurationCache(file)
            assertEquals(8000L, restored.get(uri, 64, 123))
            assertNull(restored.get(uri, 65, 123))
            assertNull(restored.get(uri, 64, 124))
            assertNull(restored.get(uri, 64, 0))
        } finally { assertTrue(file.delete()) }
    }
    @Test fun corruptCacheIsDisposableAndUnknownDurationsAreNotRetained() {
        val file = File.createTempFile("owned-duration-", ".json", context.cacheDir)
        try {
            file.writeText("broken")
            val cache = MediaDurationCache(file)
            assertNull(cache.get(uri, 64, 123))
            cache.put(uri, 64, 123, 0)
            assertNull(cache.get(uri, 64, 123))
            cache.put(uri, 64, 123, 8000)
            cache.flush()
            assertTrue("Persisted cache content: ${file.readText()}", file.readText().contains("8000"))
            assertEquals(8000L, MediaDurationCache(file).get(uri, 64, 123))
        } finally { assertTrue(file.delete()) }
    }
    @Test fun boundedCacheEvictsTheLeastRecentlyUsedFile() {
        val cache = MediaDurationCache()
        repeat(4096) { cache.put(Uri.parse("content://owned/$it"), 64, 123, 8000) }
        assertEquals(8000L, cache.get(Uri.parse("content://owned/0"), 64, 123))
        cache.put(uri, 64, 123, 8000)
        assertNull(cache.get(Uri.parse("content://owned/1"), 64, 123))
        assertEquals(8000L, cache.get(Uri.parse("content://owned/0"), 64, 123))
    }
}
