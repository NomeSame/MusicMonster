package com.nomesame.musicmonster

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.BackgroundRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric tests for the custom-background persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundRepositoryTest {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repo: BackgroundRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("bg_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        repo = BackgroundRepository(prefs)
    }

    @Test
    fun defaultsWhenNothingStored() {
        assertTrue("Background should default to enabled", repo.isEnabled())
        assertNull(repo.uri())
        assertEquals(BackgroundRepository.DEFAULT_SCRIM, repo.scrim(), 0f)
    }

    @Test
    fun enabledRoundTrip() {
        repo.setEnabled(false)
        assertFalse(repo.isEnabled())
        repo.setEnabled(true)
        assertTrue(repo.isEnabled())
    }

    @Test
    fun uriRoundTrip() {
        val uri = Uri.parse("content://media/external/images/media/42")
        repo.setUri(uri)
        assertEquals(uri, repo.uri())
        repo.setUri(null)
        assertNull(repo.uri())
    }

    @Test
    fun corruptStoredUriStringsNeverCrash() {
        // Corrupt persisted values must never crash load. BackgroundRepository
        // guards Uri.parse with runCatching, so every hostile string below must
        // yield null or a Uri — never an exception. This is the regression test
        // for the corrupt-persisted-state invariant.
        val corrupt = listOf(
            "not a valid uri:///",
            "://",
            "http://[",
            "",
            "    ",
            "content://   ",
            "a b c",
            "%%%",
            "%"
        )
        for (bad in corrupt) {
            prefs.edit().putString(BackgroundRepository.KEY_URI, bad).apply()
            repo.uri() // must not throw, whatever Uri.parse yields
        }
    }

    @Test
    fun scrimClampsToUnitRange() {
        repo.setScrim(-1f)
        assertEquals(0f, repo.scrim(), 0f)
        repo.setScrim(2f)
        assertEquals(1f, repo.scrim(), 0f)
        repo.setScrim(0.42f)
        assertEquals(0.42f, repo.scrim(), 0f)
    }

    @Test
    fun scrimReadClampsCorruptStoredValue() {
        prefs.edit().putFloat(BackgroundRepository.KEY_SCRIM, 5f).apply()
        assertEquals(1f, repo.scrim(), 0f)
        prefs.edit().putFloat(BackgroundRepository.KEY_SCRIM, -3f).apply()
        assertEquals(0f, repo.scrim(), 0f)
    }
}
