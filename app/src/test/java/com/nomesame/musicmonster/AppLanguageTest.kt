package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.LanguageRepository
import com.nomesame.musicmonster.localization.appLanguageContext
import com.nomesame.musicmonster.model.AppLanguage
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AppLanguageTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("language_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun missingUnknownAndTypeMismatchedPreferencesFollowSystem() {
        val repo = LanguageRepository(prefs)
        assertEquals(AppLanguage.SYSTEM, repo.load())
        listOf("fr", "de-DE", "DE", " Deutsch ", "null", "🎵").forEach {
            prefs.edit().putString(LanguageRepository.KEY_LANGUAGE, it).commit()
            assertEquals(AppLanguage.SYSTEM, repo.load())
        }
        prefs.edit().putInt(LanguageRepository.KEY_LANGUAGE, 42).commit()
        assertEquals(AppLanguage.SYSTEM, repo.load())
    }

    @Test fun everyLanguageSurvivesRepositoryRecreationIncludingSystemReset() {
        listOf(AppLanguage.GERMAN, AppLanguage.ENGLISH, AppLanguage.SYSTEM).forEach {
            LanguageRepository(prefs).save(it)
            assertEquals(it, LanguageRepository(prefs).load())
        }
    }

    @Test fun scopedOverrideNeverChangesBaseConfigurationOrGlobalLocale() {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags("fr-FR,en-US"))
        val base = context.createConfigurationContext(configuration)
        val default = Locale.getDefault()
        listOf(AppLanguage.GERMAN, AppLanguage.ENGLISH).forEach {
            val localized = appLanguageContext(base, it)
            assertEquals(it.tag, localized.resources.configuration.locales[0].language)
            assertEquals("fr", base.resources.configuration.locales[0].language)
            assertEquals(default, Locale.getDefault())
        }
        assertSame(base, appLanguageContext(base, AppLanguage.SYSTEM))
    }

    @Test fun viewModelPersistsLanguageWithoutChangingSelectionOrPlaylists() {
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        val store = ViewModelStore()
        try {
            val vm = MainViewModel(app)
            store.put("first", vm)
            vm.applyLibrarySongs(listOf(Song("a", "Ä song", android.net.Uri.EMPTY, 1000)))
            vm.songSelection.start("a")
            val selection = vm.songSelection.state.value
            vm.setAppLanguage(AppLanguage.GERMAN)
            vm.setAppLanguage(AppLanguage.GERMAN)
            assertEquals(AppLanguage.GERMAN, vm.appLanguage.value)
            assertEquals(selection, vm.songSelection.state.value)
            assertEquals("Ä song", vm.songs.value.single().title)
            assertTrue(vm.playlists.isEmpty())
            val recreated = MainViewModel(app)
            store.put("second", recreated)
            assertEquals(AppLanguage.GERMAN, recreated.appLanguage.value)
            recreated.setAppLanguage(AppLanguage.SYSTEM)
            assertEquals(AppLanguage.SYSTEM, LanguageRepository(prefs).load())
        } finally {
            store.clear()
        }
    }
}
