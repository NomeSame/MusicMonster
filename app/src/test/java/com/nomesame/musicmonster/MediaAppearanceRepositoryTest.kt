package com.nomesame.musicmonster

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class MediaAppearanceRepositoryTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: MediaAppearanceRepository

    @Before fun setup() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("appearance_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = MediaAppearanceRepository(prefs)
    }

    @Test fun defaultMatchesAppDesign() {
        val appearance = repository.load()
        assertTrue(appearance.backgroundEnabled)
        assertNull(appearance.backgroundUri)
        assertEquals(0.75f, appearance.scrim, 0f)
        assertEquals(0xFFE58B3C.toInt(), appearance.accent)
    }

    @Test fun sharedBackgroundSettingsRoundTrip() {
        val background = BackgroundRepository(prefs)
        background.setUri(Uri.parse("content://fixture/ä image"))
        background.setEnabled(false)
        background.setScrim(0.2f)
        prefs.edit().putInt(MediaAppearanceRepository.KEY_ACCENT, 0xFF123456.toInt()).commit()
        val appearance = MediaAppearanceRepository(prefs).load()
        assertFalse(appearance.backgroundEnabled)
        assertEquals("content://fixture/ä image", appearance.backgroundUri)
        assertEquals(0.2f, appearance.scrim, 0f)
        assertEquals(0xFF123456.toInt(), appearance.accent)
    }

    @Test fun corruptPreferenceTypesAndNonFiniteScrimUseDefaults() {
        prefs.edit().putInt(BackgroundRepository.KEY_ENABLED, 1)
            .putBoolean(BackgroundRepository.KEY_URI, true)
            .putString(MediaAppearanceRepository.KEY_ACCENT, "invalid")
            .putFloat(BackgroundRepository.KEY_SCRIM, Float.NaN).commit()
        val appearance = repository.load()
        assertTrue(appearance.backgroundEnabled)
        assertNull(appearance.backgroundUri)
        assertEquals(MediaAppearanceRepository.DEFAULT_ACCENT, appearance.accent)
        assertEquals(BackgroundRepository.DEFAULT_SCRIM, appearance.scrim, 0f)
    }

    @Test fun accentDoesNotInvalidateImageButEveryBackgroundChangeDoes() {
        val original = repository.load()
        assertEquals(original.imageKey, original.copy(accent = 123).imageKey)
        assertNotEquals(original.imageKey, original.copy(backgroundEnabled = false).imageKey)
        assertNotEquals(original.imageKey, original.copy(backgroundUri = "file://new").imageKey)
        assertNotEquals(original.imageKey, original.copy(scrim = 0f).imageKey)
    }

    @Test fun observersIgnoreUnrelatedPreferencesAndUnsubscribe() {
        var notifications = 0
        val stop = repository.observe { notifications++ }
        prefs.edit().putInt("unrelated", 1).commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, notifications)
        prefs.edit().putInt(MediaAppearanceRepository.KEY_ACCENT, 1).commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, notifications)
        stop()
        BackgroundRepository(prefs).setEnabled(false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, notifications)
    }
}
