package com.nomesame.musicmonster.ui

import androidx.annotation.StringRes
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import com.nomesame.musicmonster.state.ImportErrorCode

/**
 * Pure code→text mapping for the import flow. Kept out of the composables so it can be unit
 * tested on the JVM/Robolectric: an unknown plugin/host code must degrade to a generic message
 * instead of crashing or showing an empty string, and [PluginProtocol.ErrorCode.NONE] must
 * never be treated as a failure.
 *
 * Technical codes and file names are never translated — only the user-facing message is.
 */
object ImportText {

    /** Localized message resource for a failure code; always a real text (never `0`). */
    @StringRes
    fun error(errorCode: Int): Int = when (errorCode) {
        PluginProtocol.ErrorCode.INVALID_INPUT -> R.string.import_error_invalid_input
        PluginProtocol.ErrorCode.UNSUPPORTED_SOURCE -> R.string.import_error_unsupported_source
        PluginProtocol.ErrorCode.SOURCE_UNAVAILABLE -> R.string.import_error_source_unavailable
        PluginProtocol.ErrorCode.SOURCE_ACCESS_DENIED -> R.string.import_error_source_access_denied
        PluginProtocol.ErrorCode.NETWORK -> R.string.import_error_network
        PluginProtocol.ErrorCode.CONVERSION_FAILED -> R.string.import_error_conversion_failed
        PluginProtocol.ErrorCode.STORAGE -> R.string.import_error_storage
        PluginProtocol.ErrorCode.TOO_LARGE -> R.string.import_error_too_large
        PluginProtocol.ErrorCode.TIMEOUT -> R.string.import_error_timeout
        PluginProtocol.ErrorCode.INTERNAL -> R.string.import_error_internal
        PluginProtocol.ErrorCode.BUSY -> R.string.import_error_busy
        ImportErrorCode.TARGET_NOT_WRITABLE -> R.string.import_error_target_not_writable
        ImportErrorCode.NO_TARGET -> R.string.import_error_no_target
        ImportErrorCode.WRITE_FAILED -> R.string.import_error_write_failed
        ImportErrorCode.PLUGIN_UNREACHABLE -> R.string.import_error_plugin_unreachable
        // Includes NONE and every unknown code: a defined generic message, never blank/0.
        else -> R.string.import_error_generic
    }

    /** True only for a real failure; [PluginProtocol.ErrorCode.NONE] (= 0) is not one. */
    fun isError(errorCode: Int): Boolean = errorCode != PluginProtocol.ErrorCode.NONE

    /** Localized phase label for an active job ([PluginProtocol.JobState]). */
    @StringRes
    fun phase(state: Int): Int = when (state) {
        PluginProtocol.JobState.VALIDATING -> R.string.import_phase_validating
        PluginProtocol.JobState.DOWNLOADING -> R.string.import_phase_downloading
        PluginProtocol.JobState.CONVERTING -> R.string.import_phase_converting
        PluginProtocol.JobState.DELIVERING -> R.string.import_phase_delivering
        else -> R.string.import_phase_working
    }
}
