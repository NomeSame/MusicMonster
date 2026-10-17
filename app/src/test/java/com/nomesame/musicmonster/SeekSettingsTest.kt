package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.SeekSettingsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class SeekSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("seek_fixture", Context.MODE_PRIVATE)

    @Test fun missingCorruptAndWrongTypeUseDefaultWithoutWritingOnLoad() {
        prefs.edit().clear().commit()
        val repo = SeekSettingsRepository(prefs)
        assertEquals(10, repo.loadSeconds())
        assertFalse(prefs.contains(SeekSettingsRepository.KEY_SECONDS))
        listOf(-5, 0, 7, Int.MAX_VALUE).forEach {
            prefs.edit().putInt(SeekSettingsRepository.KEY_SECONDS, it).commit()
            assertEquals(10, repo.loadSeconds())
        }
        prefs.edit().putString(SeekSettingsRepository.KEY_SECONDS, "25").commit()
        assertEquals(10, repo.loadSeconds())
    }
    @Test fun buttonToggleDefaultsOnSurvivesRecreationAndKeepsInterval() {
        prefs.edit().clear().commit()
        val repo = SeekSettingsRepository(prefs)
        assertTrue(repo.buttonsEnabled())
        assertFalse(prefs.contains(SeekSettingsRepository.KEY_BUTTONS_ENABLED))
        repo.saveSeconds(35)
        repo.setButtonsEnabled(false)
        assertFalse(SeekSettingsRepository(prefs).buttonsEnabled())
        assertEquals(35, repo.loadSeconds())
        repo.setButtonsEnabled(true)
        assertTrue(SeekSettingsRepository(prefs).buttonsEnabled())
        prefs.edit().putString(SeekSettingsRepository.KEY_BUTTONS_ENABLED, "false").commit()
        assertTrue(repo.buttonsEnabled())
    }

    @Test fun everyChoiceSurvivesRecreationAndUnrelatedPreferencesStayIntact() {
        prefs.edit().clear().putString("untouched", "🎵").commit()
        SeekStep.options.forEach {
            assertEquals(it, SeekSettingsRepository(prefs).saveSeconds(it))
            assertEquals(it, SeekSettingsRepository(prefs).loadSeconds())
        }
        assertEquals("🎵", prefs.getString("untouched", null))
        assertEquals(10, SeekSettingsRepository(prefs).saveSeconds(3))
    }
    @Test fun viewModelPersistsIntervalWithoutStartingPlaybackOrChangingSelection() {
        prefs.edit().clear().commit()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
            override fun startService(intent: android.content.Intent): android.content.ComponentName? =
                throw AssertionError("Settings must not start playback")
            override fun startForegroundService(intent: android.content.Intent): android.content.ComponentName? =
                throw AssertionError("Settings must not start playback")
        }
        val store = ViewModelStore()
        try {
            val vm = MainViewModel(app).also { store.put("first", it) }
            vm.songSelection.start("fixture")
            val selection = vm.songSelection.state.value
            assertTrue(vm.seekButtonsEnabled.value)
            vm.setSeekButtonsEnabled(false)
            assertFalse(vm.seekButtonsEnabled.value)
            assertEquals(10, vm.seekSeconds.value)
            vm.setSeekSeconds(35)
            assertEquals(35, vm.seekSeconds.value)
            assertEquals(selection, vm.songSelection.state.value)
            val recreated = MainViewModel(app).also { store.put("second", it) }
            assertFalse(recreated.seekButtonsEnabled.value)
            recreated.setSeekButtonsEnabled(true)
            assertTrue(recreated.seekButtonsEnabled.value)
            assertEquals(35, recreated.seekSeconds.value)
            recreated.setSeekSeconds(0)
            assertEquals(10, recreated.seekSeconds.value)
        } finally { store.clear() }
    }
}
