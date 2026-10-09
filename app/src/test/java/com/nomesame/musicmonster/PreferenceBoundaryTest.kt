package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.BackgroundRepository
import com.nomesame.musicmonster.data.floatOr
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PreferenceBoundaryTest {
    private lateinit var app: Application
    private lateinit var prefs: android.content.SharedPreferences
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        prefs = app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
    @Test fun nonFiniteFloatPreferencesUseDefault() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            prefs.edit().putFloat("value", value).commit()
            assertEquals(0.7f, prefs.floatOr("value", 0.7f), 0f)
        }
    }
    @Test fun nanScrimWriteUsesSafeDefault() {
        val repo = BackgroundRepository(prefs)
        repo.setScrim(Float.NaN)
        assertEquals(BackgroundRepository.DEFAULT_SCRIM, repo.scrim(), 0f)
        assertTrue(prefs.getFloat(BackgroundRepository.KEY_SCRIM, -1f).isFinite())
    }
    @Test fun nanPlayerOpacityWriteCannotReachUi() {
        val vm = MainViewModel(app)
        vm.setPlayerOpacity(Float.NaN)
        assertEquals(0.3f, vm.playerOpacity.value, 0f)
        assertTrue(prefs.getFloat("player_opacity", -1f).isFinite())
    }
    @Test fun nanViewModelScrimWriteCannotReachUi() {
        val vm = MainViewModel(app)
        vm.setCustomBgScrim(Float.NaN)
        assertEquals(BackgroundRepository.DEFAULT_SCRIM, vm.customBgScrim.value, 0f)
    }
    @Test fun restoredNanOpacityUsesDefault() {
        prefs.edit().putFloat("player_opacity", Float.NaN).commit()
        assertEquals(0.3f, MainViewModel(app).playerOpacity.value, 0f)
    }
    @Test fun maximumPlaylistSequenceDoesNotProduceNegativeId() {
        prefs.edit().putString("playlists_json", """[{"id":"playlist_2147483647","name":"Max"}]""").commit()
        val vm = MainViewModel(app)
        vm.loadPlaylists()
        val first = vm.createPlaylist("First", null)
        val second = vm.createPlaylist("Second", null)
        assertFalse(first.id.contains("_-"))
        assertFalse(second.id.contains("_-"))
        assertEquals(3, vm.playlists.map { it.id }.toSet().size)
    }
    @Test fun missingPlayerOpacityDefaultsToThirtyPercent() {
        assertEquals(0.3f, MainViewModel(app).playerOpacity.value, 0f)
        assertFalse(prefs.contains("player_opacity"))
    }
    @Test fun newDefaultDoesNotOverwriteSavedPlayerOpacity() {
        prefs.edit().putFloat("player_opacity", 0.8f).commit()
        assertEquals(0.8f, MainViewModel(app).playerOpacity.value, 0f)
        assertEquals(0.8f, prefs.getFloat("player_opacity", -1f), 0f)
    }

}
