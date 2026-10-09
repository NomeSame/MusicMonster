package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import com.nomesame.musicmonster.model.ArtworkCrop
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ArtworkCropRepositoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    private val repository = MediaAppearanceRepository(prefs)
    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun missingBundledCropStartsAtTopWithTwoTimesZoom() {
        assertEquals(ArtworkCrop(0.5f, 0f, 2f), repository.load().crop)
    }
    @Test fun positionPersistsAndSurvivesViewModelRecreationWithoutStartingPlayback() {
        MainViewModel(app).artworkCrop.setPosition(ArtworkCrop(0.2f, 0.8f))
        assertEquals(ArtworkCrop(0.2f, 0.8f), repository.loadCrop())
        assertEquals(ArtworkCrop(0.2f, 0.8f), MainViewModel(app).artworkCrop.position.value)
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }
    @Test fun corruptPreferenceTypesAndNanNeverReachGeometry() {
        prefs.edit().putString(MediaAppearanceRepository.KEY_CROP_X, "bad")
            .putFloat(MediaAppearanceRepository.KEY_CROP_Y, Float.NaN).commit()
        assertEquals(ArtworkCrop(0.5f, 0f, 2f), repository.loadCrop())
        repository.saveCrop(ArtworkCrop(-2f, Float.POSITIVE_INFINITY))
        assertEquals(ArtworkCrop(0f, 0.5f), repository.loadCrop())
    }
    @Test fun cropInvalidatesArtworkButNotOriginalBackgroundSettings() {
        val original = repository.load()
        repository.saveCrop(ArtworkCrop(1f, 0f))
        val changed = repository.load()
        assertNotEquals(original.imageKey, changed.imageKey)
        assertEquals(original.backgroundUri, changed.backgroundUri)
        assertEquals(original.scrim, changed.scrim, 0f)
        assertEquals(original.accent, changed.accent)
    }
    @Test fun observersReceiveCropChangesAndStopAfterUnsubscribe() {
        var calls = 0
        val unsubscribe = repository.observe { calls++ }
        repository.saveCrop(ArtworkCrop(0f, 0f))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(calls > 0)
        unsubscribe()
        val previous = calls
        repository.saveCrop(ArtworkCrop())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(previous, calls)
    }
    @Test fun zoomPersistsInvalidatesArtworkAndCorruptValuesUseBackgroundDefault() {
        val original = repository.load()
        repository.saveCrop(ArtworkCrop(0.2f, 0.1f, 2.5f))
        assertEquals(ArtworkCrop(0.2f, 0.1f, 2.5f), repository.loadCrop())
        assertNotEquals(original.imageKey, repository.load().imageKey)
        prefs.edit().putString(MediaAppearanceRepository.KEY_CROP_ZOOM, "bad").commit()
        assertEquals(2f, repository.loadCrop().zoom, 0f)
        prefs.edit().putFloat(MediaAppearanceRepository.KEY_CROP_ZOOM, Float.POSITIVE_INFINITY).commit()
        assertEquals(2f, repository.loadCrop().zoom, 0f)
    }

    @Test fun customImagesKeepCenteredUnzoomedDefaultsAndResetReturnsToBundledDefaults() {
        prefs.edit().putString(com.nomesame.musicmonster.data.BackgroundRepository.KEY_URI,
            "content://fixture/custom-image").commit()
        assertEquals(ArtworkCrop(), repository.loadCrop())
        assertEquals(ArtworkCrop(), repository.load().crop)
        prefs.edit().remove(com.nomesame.musicmonster.data.BackgroundRepository.KEY_URI).commit()
        assertEquals(ArtworkCrop(0.5f, 0f, 2f), repository.loadCrop())
    }

    @Test fun savedUserCropIncludingPreviousDefaultsIsNeverOverwritten() {
        repository.saveCrop(ArtworkCrop())
        assertEquals(ArtworkCrop(), repository.loadCrop())
        assertEquals(ArtworkCrop(), MainViewModel(app).artworkCrop.position.value)
        repository.saveCrop(ArtworkCrop(0.1f, 0.9f, 3.5f))
        prefs.edit().putString(com.nomesame.musicmonster.data.BackgroundRepository.KEY_URI,
            "content://fixture/custom-image").commit()
        assertEquals(ArtworkCrop(0.1f, 0.9f, 3.5f), repository.loadCrop())
    }

    @Test fun missingFieldsUseBundledDefaultsWhileSavedFieldsAreRetained() {
        prefs.edit().putFloat(MediaAppearanceRepository.KEY_CROP_X, 0.2f).commit()
        assertEquals(ArtworkCrop(0.2f, 0f, 2f), repository.loadCrop())
        assertEquals(ArtworkCrop(0.2f, 0f, 2f), MainViewModel(app).artworkCrop.position.value)
        assertFalse(prefs.contains(MediaAppearanceRepository.KEY_CROP_Y))
        assertFalse(prefs.contains(MediaAppearanceRepository.KEY_CROP_ZOOM))
    }
}
