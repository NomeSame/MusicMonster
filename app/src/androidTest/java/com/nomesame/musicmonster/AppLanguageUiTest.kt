package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.LanguageRepository
import com.nomesame.musicmonster.localization.appLanguageContext
import com.nomesame.musicmonster.model.AppLanguage
import com.nomesame.musicmonster.ui.components.AppLanguageContent
import com.nomesame.musicmonster.ui.screens.PlayerScreen
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private lateinit var vm: MainViewModel
    private lateinit var prefs: SharedPreferences
    private val store = ViewModelStore()

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences("language_ui_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = MainViewModel(app)
            store.put("fixture", vm)
            vm.applyLibrarySongs(listOf(Song("a", "My unchanged song", Uri.EMPTY, 1000)))
        }
        host = launchComposeHost()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent {
                AppLanguageContent(vm.appLanguage.collectAsState().value) {
                    MyApplicationTheme(accent = Color(0xFFE58B3C)) {
                        PlayerScreen(vm, {}, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (::host.isInitialized) host.finish()
            store.clear()
        }
        if (::prefs.isInitialized) prefs.edit().clear().commit()
    }

    @Test fun switchingUpdatesOpenDialogAndUnderlyingScreenWithoutRecreatingHost() {
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("language_GERMAN").performClick().assertIsSelected()
        compose.onNodeWithText("App-Sprache").assertExists()
        compose.onNodeWithTag("settings_close").assertTextEquals("Schließen")
        compose.onNodeWithTag("language_ENGLISH").performClick().assertIsSelected()
        compose.onNodeWithText("App language").assertExists()
        compose.onNodeWithTag("settings_close").assertTextEquals("Close").performClick()
        compose.onNodeWithText("No song selected").assertExists()
        compose.runOnIdle {
            assertFalse(host.isFinishing)
            assertEquals(AppLanguage.ENGLISH, LanguageRepository(prefs).load())
        }
    }

    @Test fun languageChangePreservesActiveSongSelectionAndTranslatedPlurals() {
        compose.onNodeWithTag("song_row_a").performTouchInput { longClick() }
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("language_GERMAN").performClick()
        compose.onNodeWithTag("settings_close").performClick()
        compose.onNodeWithText("1 Song ausgewählt").assertExists()
        compose.onNodeWithText("Alle abwählen").assertExists()
        compose.onNodeWithTag("song_row_a").assertIsOn()
        compose.onNodeWithText("My unchanged song").assertExists()
        compose.runOnIdle { assertEquals(setOf("a"), vm.songSelection.state.value.ids) }
    }

    @Test fun returningToSystemUsesActualDeviceResourcesAndPersistsReset() {
        compose.onNodeWithTag("app_settings").performClick()
        compose.onNodeWithTag("language_GERMAN").performClick()
        compose.onNodeWithTag("language_SYSTEM").performClick().assertIsSelected()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.app_language)).assertExists()
        compose.runOnIdle { assertEquals(AppLanguage.SYSTEM, LanguageRepository(prefs).load()) }
    }

    @Test fun scopedResourcesOverrideGermanDeviceAndRestoreUnsupportedSystemFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags("de-DE"))
        val german = context.createConfigurationContext(config)
        assertEquals("Select all", appLanguageContext(german, AppLanguage.ENGLISH).getString(R.string.select_all))
        assertEquals("Alle wählen", appLanguageContext(german, AppLanguage.SYSTEM).getString(R.string.select_all))
        config.setLocales(LocaleList.forLanguageTags("fr-FR"))
        val french = context.createConfigurationContext(config)
        assertEquals("Alle wählen", appLanguageContext(french, AppLanguage.GERMAN).getString(R.string.select_all))
        assertEquals("Select all", appLanguageContext(french, AppLanguage.SYSTEM).getString(R.string.select_all))
    }

    @Test fun visibilityFlagAffectsEntryButKeepsLanguageFunctionAvailable() {
        if (BuildConfig.SHOW_LANGUAGE_SETTINGS) {
            compose.onNodeWithTag("app_settings").assertExists()
        } else {
            compose.onNodeWithTag("app_settings").assertDoesNotExist()
        }
        compose.runOnIdle { vm.setAppLanguage(AppLanguage.GERMAN) }
        compose.onNodeWithText("Kein Song ausgewählt").assertExists()
        compose.runOnIdle { assertEquals(AppLanguage.GERMAN, LanguageRepository(prefs).load()) }
    }

    @Test fun existingPlaylistDialogChangesLanguageWithoutLosingTypedName() {
        compose.runOnIdle { vm.setAppLanguage(AppLanguage.GERMAN) }
        compose.onNodeWithTag("song_row_a").performTouchInput { longClick() }
        compose.onNodeWithTag("selection_create").performClick()
        compose.onNodeWithText("Abbrechen").assertExists()
        compose.onNodeWithTag("playlist_name").performTextInput("Meine Liste")
        compose.runOnIdle { vm.setAppLanguage(AppLanguage.ENGLISH) }
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithTag("playlist_name").assertTextContains("Meine Liste")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("song_row_a").assertIsOn()
    }

    @Test fun existingPersonalizationDialogChangesLanguageToo() {
        compose.runOnIdle { vm.setAppLanguage(AppLanguage.GERMAN) }
        compose.onNodeWithContentDescription("Akzentfarbe").performClick()
        compose.onNodeWithText("Akzentfarbe").assertExists()
        compose.runOnIdle { vm.setAppLanguage(AppLanguage.ENGLISH) }
        compose.onNodeWithText("Accent color").assertExists()
        compose.onNodeWithText("Done").assertExists()
    }
}
