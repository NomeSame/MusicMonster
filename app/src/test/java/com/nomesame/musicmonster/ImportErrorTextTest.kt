package com.nomesame.musicmonster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import com.nomesame.musicmonster.state.ImportErrorCode
import com.nomesame.musicmonster.ui.ImportText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import code→text mapping is a pure function; this test pins that every known plugin code
 * (0–11) and every host code (101+) resolves to a real, code-specific EN text, that unknown codes
 * degrade to a generic message (never crash/empty) and that NONE is never treated as a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ImportErrorTextTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(code: Int): String = context.getString(ImportText.error(code))

    private val pluginCodes = listOf(
        PluginProtocol.ErrorCode.INVALID_INPUT,
        PluginProtocol.ErrorCode.UNSUPPORTED_SOURCE,
        PluginProtocol.ErrorCode.SOURCE_UNAVAILABLE,
        PluginProtocol.ErrorCode.NETWORK,
        PluginProtocol.ErrorCode.CONVERSION_FAILED,
        PluginProtocol.ErrorCode.STORAGE,
        PluginProtocol.ErrorCode.TOO_LARGE,
        PluginProtocol.ErrorCode.TIMEOUT,
        PluginProtocol.ErrorCode.INTERNAL,
        PluginProtocol.ErrorCode.BUSY,
        PluginProtocol.ErrorCode.SOURCE_ACCESS_DENIED,
    )

    private val hostCodes = listOf(
        ImportErrorCode.TARGET_NOT_WRITABLE,
        ImportErrorCode.NO_TARGET,
        ImportErrorCode.WRITE_FAILED,
        ImportErrorCode.PLUGIN_UNREACHABLE,
    )

    @Test fun everyKnownPluginCodeHasItsOwnNonBlankText() {
        pluginCodes.forEach { code ->
            assertTrue("blank text for plugin code $code", text(code).isNotBlank())
            assertNotEquals("plugin code $code used the generic fallback",
                R.string.import_error_generic, ImportText.error(code))
        }
        assertEquals("known plugin codes must be distinguishable",
            pluginCodes.size, pluginCodes.map { ImportText.error(it) }.distinct().size)
    }

    @Test fun everyHostCodeHasItsOwnNonBlankText() {
        hostCodes.forEach { code ->
            assertTrue("blank text for host code $code", text(code).isNotBlank())
            assertNotEquals("host code $code used the generic fallback",
                R.string.import_error_generic, ImportText.error(code))
        }
        assertEquals("known host codes must be distinguishable",
            hostCodes.size, hostCodes.map { ImportText.error(it) }.distinct().size)
        // plugin and host ranges must not collide
        assertTrue(pluginCodes.map { ImportText.error(it) }.toSet()
            .intersect(hostCodes.map { ImportText.error(it) }.toSet()).isEmpty())
    }

    @Test fun unknownCodesFallBackToTheGenericText() {
        val generic = R.string.import_error_generic
        listOf(0, 99, -1, 100, 105, Int.MAX_VALUE, Int.MIN_VALUE).forEach { code ->
            assertEquals("code $code", generic, ImportText.error(code))
            assertTrue("code $code produced a blank text", text(code).isNotBlank())
        }
    }

    @Test fun noneIsNeverTreatedAsAnError() {
        assertFalse(ImportText.isError(PluginProtocol.ErrorCode.NONE))
        (pluginCodes + hostCodes).forEach { code ->
            assertTrue("code $code must count as an error", ImportText.isError(code))
        }
        // unknown codes still count as errors if they ever reach the UI
        assertTrue(ImportText.isError(99))
    }

    @Test fun everyActivePhaseHasANonBlankLabel() {
        val phases = listOf(
            PluginProtocol.JobState.VALIDATING,
            PluginProtocol.JobState.DOWNLOADING,
            PluginProtocol.JobState.CONVERTING,
            PluginProtocol.JobState.DELIVERING,
        )
        phases.forEach { phase ->
            assertTrue("blank phase label for $phase",
                context.getString(ImportText.phase(phase)).isNotBlank())
        }
        assertEquals(phases.size, phases.map { ImportText.phase(it) }.distinct().size)
        // an unknown phase degrades to a generic label instead of crashing
        assertTrue(context.getString(ImportText.phase(999)).isNotBlank())
    }
}
