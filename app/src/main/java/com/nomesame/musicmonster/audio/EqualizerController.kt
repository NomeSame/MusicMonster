package com.nomesame.musicmonster.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import java.util.Locale
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * Owns the [Equalizer] and [BassBoost] audio effects plus their observable
 * Compose state. Extracted verbatim from MainActivity; behavior is unchanged.
 *
 * State is exposed as Compose snapshot state so UI observing it recomposes.
 * The effect objects are reassigned only from within this controller.
 */
class EqualizerController {

    val audioSessionId = mutableStateOf(0)
    val eqEnabled = mutableStateOf(true)
    val eqBandLevels = mutableStateListOf<Int>()
    val eqBandCount = mutableStateOf(0)
    val eqBandHz = mutableStateListOf<Int>()
    val bassBoostEnabled = mutableStateOf(false)
    val bassBoostStrength = mutableStateOf(600)
    val eqPresetLabel = mutableStateOf("Flat")

    var equalizer: Equalizer? = null
        private set
    var bassBoost: BassBoost? = null
        private set

    /**
     * Preset curve for [label]. Every call into [equalizer] here goes through
     * the OEM AudioFX implementation, which is free to throw (dead session,
     * vendor effect that reports presets it can't apply); a preset button must
     * never be able to crash the app, so failures fall back to a flat curve.
     */
    fun buildPresetLevels(label: String, equalizer: Equalizer): List<Int> =
        runCatching { buildPresetLevelsUnsafe(label, equalizer) }
            .getOrElse { List(eqBandCount.value.coerceAtLeast(0)) { 0 } }

    private fun buildPresetLevelsUnsafe(label: String, equalizer: Equalizer): List<Int> {
        val bandCount = equalizer.numberOfBands.toInt()
        val range = equalizer.bandLevelRange
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val curve = curveFor(label, minLevel, maxLevel)

        if (equalizer.numberOfPresets > 0) {
            for (i in 0 until equalizer.numberOfPresets) {
                val preset = i.toShort()
                val name = equalizer.getPresetName(preset).lowercase(Locale.ROOT)
                if (name.contains(label.lowercase(Locale.ROOT))) {
                    equalizer.usePreset(preset)
                    return List(bandCount) { bandIndex ->
                        equalizer.getBandLevel(bandIndex.toShort()).toInt()
                    }
                }
            }
        }

        return List(bandCount) { bandIndex ->
            val idx = (bandIndex.toFloat() / (bandCount - 1).coerceAtLeast(1)).times(4).toInt()
                .coerceIn(0, 4)
            curve[idx].coerceIn(minLevel, maxLevel)
        }
    }

    companion object {
        /**
         * The five-point gain curve for a preset [label], scaled to the
         * device's own [minLevel]/[maxLevel] band range. Pure and device-free
         * so the label matching can be unit tested.
         *
         * Locale.ROOT matters here: the platform default lowercase() maps
         * "Classic" to a dotless-i "classıc" on Turkish and Azeri devices, so
         * no label ever matched and every preset silently collapsed to flat —
         * the same locale trap the sort comparator already guards against.
         */
        fun curveFor(label: String, minLevel: Int, maxLevel: Int): List<Int> {
            val boost = (maxLevel * 0.75f).toInt()
            val mid = (maxLevel * 0.35f).toInt()
            val cut = (minLevel * 0.6f).toInt()
            return when (label.lowercase(Locale.ROOT)) {
                "metal" -> listOf(boost, mid, 0, mid, boost)
                "rock" -> listOf(mid, boost, mid, boost, mid)
                "classic" -> listOf(cut, 0, mid, mid, cut)
                "pop" -> listOf(0, mid, boost, mid, 0)
                else -> listOf(0, 0, 0, 0, 0)
            }
        }
    }

    fun setupForSession(sessionId: Int) {
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
        if (sessionId == 0) return
        try {
            val eq = Equalizer(0, sessionId)
            eq.enabled = eqEnabled.value
            val bandCount = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            if (eqBandLevels.size != bandCount || eqBandCount.value != bandCount) {
                eqBandLevels.clear()
                eqBandHz.clear()
                repeat(bandCount) { bandIndex ->
                    val band = bandIndex.toShort()
                    eqBandLevels.add(eq.getBandLevel(band).toInt())
                    eqBandHz.add((eq.getCenterFreq(band) / 1000).toInt())
                }
                eqBandCount.value = bandCount
            } else {
                for (bandIndex in 0 until bandCount) {
                    val band = bandIndex.toShort()
                    val level = eqBandLevels[bandIndex]
                    eq.setBandLevel(band, level.toShort().coerceIn(range[0], range[1]))
                }
            }
            equalizer = eq
        } catch (_: Throwable) {
            equalizer = null
        }

        try {
            val bb = BassBoost(0, sessionId)
            bb.enabled = bassBoostEnabled.value
            bb.setStrength(bassBoostStrength.value.toShort())
            bassBoost = bb
        } catch (_: Throwable) {
            bassBoost = null
        }
    }
}
