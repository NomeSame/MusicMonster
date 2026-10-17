package com.nomesame.musicmonster.plugin.api

import com.nomesame.musicmonster.plugin.api.PluginProtocol.ErrorCode
import com.nomesame.musicmonster.plugin.api.PluginProtocol.JobState
import com.nomesame.musicmonster.plugin.api.PluginProtocol.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire contract is a shared, stable source consumed by two separately built APKs.
 *  These plain-JVM tests pin the values other code relies on, so a renumbering or an
 *  accidental change fails loudly here instead of at runtime between mismatched builds.
 */
class PluginProtocolTest {

    // ---- Existing values must stay literally unchanged --------------------------------

    @Test
    fun existingValuesAreUnchanged() {
        assertEquals(1, PluginProtocol.VERSION)
        assertEquals(1, PluginProtocol.HELLO)
        assertEquals(2, PluginProtocol.HELLO_REPLY)
        assertEquals(0, PluginProtocol.READY)
        assertEquals(1, PluginProtocol.INCOMPATIBLE)
        assertEquals(
            "com.nomesame.musicmonster.plugin.BIND_V1",
            PluginProtocol.BIND_ACTION,
        )
        assertEquals(
            "com.nomesame.musicmonster.permission.BIND_PLUGIN",
            PluginProtocol.PERMISSION,
        )
        assertEquals(
            "com.nomesame.musicmonster.plugin.PROTOCOL_VERSION",
            PluginProtocol.META_VERSION,
        )
        assertEquals(3_000L, PluginProtocol.TIMEOUT_MS)
        assertTrue(PluginProtocol.accepts(1))
        assertFalse(PluginProtocol.accepts(2))
    }

    // ---- Message ids stay unique and never collide with the reserved 1/2 --------------

    @Test
    fun newMessageIdsArePairwiseDistinctAndDoNotCollideWithExisting() {
        val ids = listOf(
            PluginProtocol.CAPS,
            PluginProtocol.CAPS_REPLY,
            PluginProtocol.JOB_START,
            PluginProtocol.JOB_REPLY,
            PluginProtocol.JOB_STATUS,
            PluginProtocol.JOB_RESULT,
            PluginProtocol.JOB_CANCEL,
            PluginProtocol.JOB_QUERY,
            PluginProtocol.JOB_RESULT_ACK,
        )
        assertEquals("message ids must not repeat", ids.size, ids.toSet().size)
        for (id in ids) {
            assertFalse("id $id collides with HELLO", id == PluginProtocol.HELLO)
            assertFalse("id $id collides with HELLO_REPLY", id == PluginProtocol.HELLO_REPLY)
        }
    }

    @Test
    fun newMessageIdsMatchTheAgreedNumbers() {
        assertEquals(3, PluginProtocol.CAPS)
        assertEquals(4, PluginProtocol.CAPS_REPLY)
        assertEquals(10, PluginProtocol.JOB_START)
        assertEquals(11, PluginProtocol.JOB_REPLY)
        assertEquals(12, PluginProtocol.JOB_STATUS)
        assertEquals(13, PluginProtocol.JOB_RESULT)
        assertEquals(14, PluginProtocol.JOB_CANCEL)
        assertEquals(15, PluginProtocol.JOB_QUERY)
        assertEquals(16, PluginProtocol.JOB_RESULT_ACK)
        assertEquals(0, PluginProtocol.JOB_ACCEPTED)
        assertEquals(1, PluginProtocol.JOB_REJECTED)
    }

    @Test
    fun bundleKeysAreDistinctAndNonEmpty() {
        val keys = listOf(
            PluginProtocol.KEY_JOB_ID,
            PluginProtocol.KEY_URL,
            PluginProtocol.KEY_STATE,
            PluginProtocol.KEY_ERROR,
            PluginProtocol.KEY_FILE_NAME,
            PluginProtocol.KEY_RESULT_URI,
            PluginProtocol.KEY_ACCEPTED,
        )
        assertEquals("bundle keys must be unique", keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotEmpty() })
    }

    // ---- JobState ----------------------------------------------------------------------

    @Test
    fun jobStateValuesMatchTheAgreedNumbers() {
        assertEquals(1, JobState.VALIDATING)
        assertEquals(2, JobState.DOWNLOADING)
        assertEquals(3, JobState.CONVERTING)
        assertEquals(4, JobState.DELIVERING)
        assertEquals(5, JobState.SUCCEEDED)
        assertEquals(6, JobState.FAILED)
        assertEquals(7, JobState.CANCELLED)
    }

    @Test
    fun onlyTerminalStatesReportTerminal() {
        assertTrue(JobState.isTerminal(JobState.SUCCEEDED))
        assertTrue(JobState.isTerminal(JobState.FAILED))
        assertTrue(JobState.isTerminal(JobState.CANCELLED))
        for (nonTerminal in listOf(
            JobState.VALIDATING,
            JobState.DOWNLOADING,
            JobState.CONVERTING,
            JobState.DELIVERING,
        )) {
            assertFalse("state $nonTerminal must not be terminal", JobState.isTerminal(nonTerminal))
            assertTrue("state $nonTerminal must be known", JobState.isKnown(nonTerminal))
        }
    }

    @Test
    fun unknownJobStatesAreRejected() {
        for (unknown in listOf(0, 8, -1, Int.MAX_VALUE)) {
            assertFalse("state $unknown must be unknown", JobState.isKnown(unknown))
            assertFalse("state $unknown must not be terminal", JobState.isTerminal(unknown))
        }
    }

    // ---- ErrorCode ---------------------------------------------------------------------

    @Test
    fun errorCodeAcceptsOnlyKnownCodes() {
        for (valid in 0..11) {
            assertTrue("code $valid should be valid", ErrorCode.isValid(valid))
        }
        for (invalid in listOf(-1, 12, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse("code $invalid should be invalid", ErrorCode.isValid(invalid))
        }
    }

    @Test
    fun errorCodeValuesMatchTheAgreedNumbers() {
        assertEquals(0, ErrorCode.NONE)
        assertEquals(1, ErrorCode.INVALID_INPUT)
        assertEquals(2, ErrorCode.UNSUPPORTED_SOURCE)
        assertEquals(3, ErrorCode.SOURCE_UNAVAILABLE)
        assertEquals(4, ErrorCode.NETWORK)
        assertEquals(5, ErrorCode.CONVERSION_FAILED)
        assertEquals(6, ErrorCode.STORAGE)
        assertEquals(7, ErrorCode.TOO_LARGE)
        assertEquals(8, ErrorCode.TIMEOUT)
        assertEquals(9, ErrorCode.INTERNAL)
        assertEquals(10, ErrorCode.BUSY)
        assertEquals(11, ErrorCode.SOURCE_ACCESS_DENIED)
    }

    // ---- Progress: clamping and monotonicity -------------------------------------------

    @Test
    fun clampForcesTheLegalZeroToHundredRange() {
        assertEquals(0, Progress.clamp(-5))
        assertEquals(0, Progress.clamp(Int.MIN_VALUE))
        assertEquals(0, Progress.clamp(0))
        assertEquals(42, Progress.clamp(42))
        assertEquals(100, Progress.clamp(100))
        assertEquals(100, Progress.clamp(150))
        assertEquals(100, Progress.clamp(Int.MAX_VALUE))
    }

    @Test
    fun advanceNeverFallsBelowTheCurrentValue() {
        assertEquals(10, Progress.advance(10, 5))
        assertEquals(10, Progress.advance(10, 10))
        assertEquals(20, Progress.advance(10, 20))
        assertEquals(100, Progress.advance(100, -50))
        assertEquals(0, Progress.advance(-10, -20))
        assertEquals(100, Progress.advance(Int.MIN_VALUE, 150))
    }

    @Test
    fun phaseBandsMatchDecisionsSectionFive() {
        assertEquals(0, Progress.VALIDATING)
        assertEquals(5, Progress.DOWNLOADING_FROM)
        assertEquals(70, Progress.DOWNLOADING_TO)
        assertEquals(70, Progress.CONVERTING_FROM)
        assertEquals(95, Progress.CONVERTING_TO)
        assertEquals(95, Progress.DELIVERING_FROM)
        assertEquals(100, Progress.DELIVERING_TO)
        assertTrue(Progress.DOWNLOADING_FROM < Progress.DOWNLOADING_TO)
        assertTrue(Progress.CONVERTING_FROM < Progress.CONVERTING_TO)
        assertTrue(Progress.DELIVERING_FROM <= Progress.DELIVERING_TO)
    }

    // ---- Capability bitmask ------------------------------------------------------------

    @Test
    fun hasCapabilityIsAPureBitmaskTest() {
        assertFalse(PluginProtocol.hasCapability(0, PluginProtocol.CAP_IMPORT))
        assertTrue(PluginProtocol.hasCapability(PluginProtocol.CAP_IMPORT, PluginProtocol.CAP_IMPORT))
        assertEquals(1, PluginProtocol.CAP_IMPORT)
        // Unknown bits must neither add nor remove the known capability.
        assertTrue(PluginProtocol.hasCapability(PluginProtocol.CAP_IMPORT or 0x4000, PluginProtocol.CAP_IMPORT))
        assertFalse(PluginProtocol.hasCapability(0x4000, PluginProtocol.CAP_IMPORT))
    }
}
