package com.nomesame.musicmonster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.PlaybackStateRepository
import com.nomesame.musicmonster.model.PlaybackResumeState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PlaybackStateRepositoryTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("resume_repository_fixture", Context.MODE_PRIVATE)
    @Test fun existingKeysRestoreSongPositionAndShuffleWithoutMigration() {
        prefs.edit().clear().putString("last_song_id", "content://song/🦄")
            .putLong("last_position", 1234567).putBoolean("shuffle_enabled", true).commit()
        assertEquals(PlaybackResumeState("content://song/🦄", 1234567, true), PlaybackStateRepository(prefs).load())
    }
    @Test fun wrongTypesAndNegativePositionsDegradeWithoutWriting() {
        prefs.edit().clear().putInt("last_song_id", 3).putString("last_position", "42")
            .putString("shuffle_enabled", "true").commit()
        val before = prefs.all.toMap()
        assertEquals(PlaybackResumeState(null, 0, false), PlaybackStateRepository(prefs).load())
        assertEquals(before, prefs.all)
        prefs.edit().putString("last_song_id", "a").putLong("last_position", Long.MIN_VALUE).commit()
        assertEquals(0L, PlaybackStateRepository(prefs).load().positionMs)
    }
    @Test fun completeSnapshotPersistsAndNoItemCannotEraseIt() {
        prefs.edit().clear().putString("unrelated", "keep").commit()
        val saved = PlaybackResumeState("second", Long.MAX_VALUE, true)
        assertTrue(PlaybackStateRepository(prefs).save(saved))
        assertEquals(saved, PlaybackStateRepository(prefs).load())
        listOf(null, "", " ").forEach {
            assertFalse(PlaybackStateRepository(prefs).save(PlaybackResumeState(it, 0, false)))
            assertEquals(saved, PlaybackStateRepository(prefs).load())
        }
        assertEquals("keep", prefs.getString("unrelated", null))
    }
}
