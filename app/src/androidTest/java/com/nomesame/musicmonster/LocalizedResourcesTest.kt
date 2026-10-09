package com.nomesame.musicmonster

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizedResourcesTest {
    private fun resources(language: String): android.content.res.Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(config).resources
    }
    @Test fun germanResourcesAreResolvedAndPluralsAreFormatted() {
        val res = resources("de-DE")
        assertEquals("Alle wählen", res.getString(R.string.select_all))
        assertEquals("Alle abwählen", res.getString(R.string.deselect_all))
        assertEquals("1 Song ausgewählt", res.getQuantityString(R.plurals.selected_songs, 1, 1))
        assertEquals("2 Songs ausgewählt", res.getQuantityString(R.plurals.selected_songs, 2, 2))
    }
    @Test fun englishResourcesAreResolvedAndFormatted() {
        val res = resources("en-US")
        assertEquals("Select all", res.getString(R.string.select_all))
        assertEquals("Add to Mix", res.getString(R.string.add_to_named_playlist, "Mix"))
        assertEquals("1 song", res.getQuantityString(R.plurals.song_count, 1, 1))
        assertEquals("2 songs", res.getQuantityString(R.plurals.song_count, 2, 2))
    }
    @Test fun unsupportedLanguageFallsBackToEnglish() {
        assertEquals("Create playlist", resources("fr-FR").getString(R.string.create_playlist))
    }
}
