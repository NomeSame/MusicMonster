package com.nomesame.musicmonster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.PlaybackStateRepository
import com.nomesame.musicmonster.data.SongReferenceRepository
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

    @Test fun queuedOldSnapshotCannotUndoRenameFromAnotherRepositoryInstance() {
        val snapshot = PlaybackResumeState("rename-original", 42000, true)
        val repository = PlaybackStateRepository(prefs)
        assertTrue(repository.save(snapshot))
        assertTrue(SongReferenceRepository(prefs).remap("rename-original", "rename-target"))
        assertTrue(repository.save(snapshot.copy(positionMs = 43000)))
        assertEquals(snapshot.copy(songId = "rename-target", positionMs = 43000), repository.load())
    }

    @Test fun renameAfterCheckpointKeepsPositionAndShuffle() {
        val repository = PlaybackStateRepository(prefs)
        val snapshot = PlaybackResumeState("before-checkpoint", 12000, true)
        assertTrue(repository.save(snapshot))
        assertTrue(SongReferenceRepository(prefs).remap("before-checkpoint", "after-checkpoint"))
        assertEquals(snapshot.copy(songId = "after-checkpoint"), repository.load())
    }

    @Test fun chainedRenamesAndRenameBackResolveQueuedIdsWithoutCycles() {
        val references = SongReferenceRepository(prefs)
        val repository = PlaybackStateRepository(prefs)
        assertTrue(references.remap("chain-a", "chain-b"))
        assertTrue(references.remap("chain-b", "chain-c"))
        assertTrue(repository.save(PlaybackResumeState("chain-a", 1, false)))
        assertEquals("chain-c", repository.load().songId)
        assertTrue(references.remap("chain-c", "chain-a"))
        for (id in listOf("chain-a", "chain-b", "chain-c")) {
            assertTrue(repository.save(PlaybackResumeState(id, 2, false)))
            assertEquals("chain-a", repository.load().songId)
        }
    }
}
