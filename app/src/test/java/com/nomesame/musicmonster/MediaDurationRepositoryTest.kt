package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.MediaDurationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaDurationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://owned/duration.mp4")

    @Test fun cachesPositiveMetadataAndInvalidatesChangedFiles() {
        var calls = 0
        val repo = MediaDurationRepository(context) { ++calls * 1000L }
        assertEquals(1000L, repo.read(uri, 10, 1))
        assertEquals(1000L, repo.read(uri, 10, 1))
        assertEquals(2000L, repo.read(uri, 11, 1))
        assertEquals(3000L, repo.read(uri, 11, 2))
        assertEquals(3, calls)
    }
    @Test fun missingStampsAndFailedReadsAreRetried() {
        var calls = 0
        val repo = MediaDurationRepository(context) { ++calls; if (calls == 1) error("grant revoked") else 8000L }
        assertEquals(0L, repo.read(uri, 10, 1))
        assertEquals(8000L, repo.read(uri, 10, 1))
        assertEquals(8000L, repo.read(uri))
        assertEquals(8000L, repo.read(uri))
        assertEquals(4, calls)
    }
    @Test fun negativeMetadataIsUnknownAndNeverCached() {
        var calls = 0
        val repo = MediaDurationRepository(context) { ++calls; -1L }
        repeat(2) { assertEquals(0L, repo.read(uri, 10, 1)) }
        assertEquals(2, calls)
    }
    @Test fun unknownTrackDurationDoesNotPretendToBeZeroLength() {
        assertEquals("\u2014", MusicLogic.formatTrackDuration(0))
        assertEquals("\u2014", MusicLogic.formatTrackDuration(-1))
        assertEquals("0:08", MusicLogic.formatTrackDuration(8000))
        assertEquals("3:21", MusicLogic.formatTrackDuration(201000))
        assertEquals("0:00", MusicLogic.formatTime(0)) // A playback clock still starts at zero.
    }
}
