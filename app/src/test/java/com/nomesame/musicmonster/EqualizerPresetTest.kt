package com.nomesame.musicmonster

import com.nomesame.musicmonster.audio.EqualizerController.Companion.curveFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Permanent regression tests for the equalizer preset curves.
 *
 * The trap: `"Classic".lowercase()` uses the *platform default* locale, and on
 * a Turkish or Azeri device that produces a dotless "classıc". Every preset
 * label therefore fell through to the flat curve — silently, on those devices
 * only, with no error anywhere. The same class of bug the natural-sort
 * comparator already guards against.
 */
class EqualizerPresetTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    private val hostileLocales = listOf(
        Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("az-AZ"),
        Locale.forLanguageTag("lt-LT"),
        Locale.forLanguageTag("en-US"),
        Locale.ROOT,
    )

    @Test
    fun presetCurvesAreIdenticalInEveryLocale() {
        val labels = listOf("Metal", "Rock", "Classic", "Pop", "Flat")
        val reference = labels.associateWith { curveFor(it, -1500, 1500) }
        for (locale in hostileLocales) {
            Locale.setDefault(locale)
            for (label in labels) {
                assertEquals(
                    "preset '$label' changed shape under locale $locale",
                    reference[label],
                    curveFor(label, -1500, 1500)
                )
            }
        }
    }

    @Test
    fun classicIsNotSilentlyFlatUnderTurkishLocale() {
        // The specific failure: "Classic" contains an 'I'/'i' pair, so it is
        // the label the Turkish mapping actually breaks.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        val flat = listOf(0, 0, 0, 0, 0)
        assertNotEquals("Classic collapsed to the flat curve", flat, curveFor("Classic", -1500, 1500))
    }

    @Test
    fun labelMatchingIsCaseInsensitive() {
        val expected = curveFor("Metal", -1500, 1500)
        listOf("metal", "METAL", "MeTaL").forEach {
            assertEquals(expected, curveFor(it, -1500, 1500))
        }
    }

    @Test
    fun unknownLabelIsFlat() {
        assertEquals(listOf(0, 0, 0, 0, 0), curveFor("does-not-exist", -1500, 1500))
        assertEquals(listOf(0, 0, 0, 0, 0), curveFor("", -1500, 1500))
    }

    @Test
    fun curveStaysInsideTheDeviceBandRange() {
        // Devices report wildly different ranges; a curve that exceeds them is
        // rejected by the AudioFX implementation.
        val ranges = listOf(-1500 to 1500, -300 to 300, 0 to 0, -9600 to 9600, -1 to 1)
        for ((min, max) in ranges) {
            for (label in listOf("Metal", "Rock", "Classic", "Pop", "Flat")) {
                curveFor(label, min, max).forEach { level ->
                    assertTrue(
                        "'$label' produced $level outside [$min, $max]",
                        level in min..max
                    )
                }
            }
        }
    }

    @Test
    fun everyCurveHasFivePoints() {
        // The band interpolation in buildPresetLevels indexes 0..4 unchecked.
        listOf("Metal", "Rock", "Classic", "Pop", "Flat", "nonsense").forEach {
            assertEquals(5, curveFor(it, -1500, 1500).size)
        }
    }
}
