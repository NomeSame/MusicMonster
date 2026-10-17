package com.nomesame.musicmonster.state

import android.net.Uri
import android.os.Bundle
import com.nomesame.musicmonster.data.ActiveImportJobStore
import com.nomesame.musicmonster.data.ImportFileNames
import com.nomesame.musicmonster.data.ImportFileWriter
import com.nomesame.musicmonster.data.WriteResult
import com.nomesame.musicmonster.plugin.ImportJobCallbacks
import com.nomesame.musicmonster.plugin.ImportJobSession
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of the one-shot URL→MP3 import. [Failed.errorCode] is either a
 * [PluginProtocol.ErrorCode] or one of the host-side [ImportErrorCode] values; both are plain
 * integers so the UI layer (Task F2) can map them to localized text without this layer
 * knowing any strings.
 */
sealed interface ImportState {

    /** Capability present, no job running. */
    object Idle : ImportState

    /** No import-capable plugin (fail-closed default); import must stay off. */
    object Unavailable : ImportState

    /** Exactly one job running; [state] is a [PluginProtocol.JobState], [progress] 0–100. */
    data class Active(val jobId: String, val state: Int, val progress: Int) : ImportState

    /** File fully written into the library; [displayName] is the concrete name used. */
    data class Succeeded(val displayName: String) : ImportState

    /** Distinguishable failure; see the interface KDoc for the code namespace. */
    data class Failed(val errorCode: Int) : ImportState

    /** Terminal `CANCELLED` reported by the plugin. */
    object Cancelled : ImportState
}

/**
 * True when the import entry must be reachable in the UI. Everything except the fail-closed
 * [ImportState.Unavailable] (no plugin, HELLO-only, incompatible or unanswered capability) shows
 * the entry; [ImportState.Unavailable] keeps it hidden so the player is unchanged.
 */
val ImportState.entryVisible: Boolean
    get() = this !is ImportState.Unavailable

/**
 * Host-side failure codes, additive to [PluginProtocol.ErrorCode] (whose numbers are never
 * changed). They live far above the plugin range so the two namespaces cannot collide.
 */
object ImportErrorCode {

    /** The SAF library folder is not writable (pre-flight check). */
    const val TARGET_NOT_WRITABLE = 101

    /** No SAF library folder is configured at all. */
    const val NO_TARGET = 102

    /** The delivered file could not be written into the library (no false success). */
    const val WRITE_FAILED = 103

    /** The plugin service could not be bound/used. */
    const val PLUGIN_UNREACHABLE = 104
}

/**
 * Owns the import flow state and orchestrates exactly one job. Layers: **State** — no binder,
 * no SAF, no UI. It talks to the plugin only through [session] and to storage only through
 * [writer], both injectable; [currentTarget] supplies the user's tree URI and [onLibraryChanged]
 * is the single outward "library changed" signal (no playback/queue mutation, no autoplay).
 *
 * Guarantees (private-plugin/DECISIONS.md §4/§5/§6/§9, FEATURE_SPEC Z. 39–40):
 *  - Import is unavailable until a fresh negotiation reports [PluginProtocol.CAP_IMPORT];
 *    nothing is trusted or cached here.
 *  - At most one job: while [ImportState.Active] a second [start] is refused without touching
 *    the session.
 *  - The target tree URI is fixed at [start] and used for exactly that job; a later folder
 *    change cannot redirect a running job.
 *  - `JOB_RESULT` is always answered with `JOB_RESULT_ACK` (also on write failure), so the
 *    plugin can revoke its grant/drop the temp file.
 *  - The active job id is remembered (when a [jobStore] is supplied) so [reattach] can ask the
 *    plugin for the live snapshot after a host restart instead of starting a second job; it is
 *    cleared in every terminal state.
 *  - Input is only sanity-checked (non-empty, `http`/`https`); the full source validation stays
 *    with the plugin and is deliberately not duplicated here.
 */
class ImportController(
    private val session: ImportJobSession,
    private val writer: ImportFileWriter,
    private val currentTarget: () -> Uri?,
    private val onLibraryChanged: () -> Unit = {},
    private val jobStore: ActiveImportJobStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    parentScope: CoroutineScope? = null,
) {
    private val scope = CoroutineScope(
        (parentScope?.coroutineContext ?: Dispatchers.Main.immediate) +
            SupervisorJob(parentScope?.coroutineContext?.get(Job)),
    )
    private var deliveringJob: String? = null
    private var deliveryTask: Job? = null
    private var preflightTask: Job? = null
    private var requestSent = false

    private val mutableState = MutableStateFlow<ImportState>(ImportState.Unavailable)

    /** Current import state; starts [ImportState.Unavailable] until capability is negotiated. */
    val state: StateFlow<ImportState> = mutableState.asStateFlow()

    private val mutableCanStart = MutableStateFlow(false)

    /** True when a new job can be started right now (capability present, no active job). */
    val canStart: StateFlow<Boolean> = mutableCanStart.asStateFlow()

    private var capable = false
    private var activeJobId: String? = null
    private var activeTarget: Uri? = null
    private var activeProgress = 0

    /** Records a fresh capability negotiation (see `PluginProbe.probeCapabilities`). Missing
     *  [PluginProtocol.CAP_IMPORT] — including an unanswered CAPS — means [ImportState.Unavailable].
     */
    fun onCapabilities(capabilities: Int) {
        capable = PluginProtocol.hasCapability(capabilities, PluginProtocol.CAP_IMPORT)
        if (!capable) {
            preflightTask?.cancel()
            deliveryTask?.cancel()
            endJob()
            setState(ImportState.Unavailable)
        } else if (mutableState.value !is ImportState.Active) {
            setState(ImportState.Idle)
        }
    }

    /**
     * Re-attaches to a job that was already running when the host (re)started. With a live
     * in-memory job it asks for a fresh snapshot (`JOB_QUERY`); after a process restart it resumes
     * the persisted job id through [ImportJobSession.resumeJob]. Never starts a second job and does
     * nothing without the import capability. The original target is restored with the job;
     * changing the current library folder must never redirect an in-flight import.
     */
    fun reattach() {
        if (!capable) return
        val live = activeJobId
        if (live != null) {
            session.query(live)
            return
        }
        if (mutableState.value is ImportState.Active) return
        val store = jobStore ?: return
        val stored = store.load() ?: return
        val storedTarget = store.loadTarget()?.let(Uri::parse) ?: run {
            store.clear()
            setState(ImportState.Failed(ImportErrorCode.NO_TARGET))
            return
        }
        activeJobId = stored
        activeTarget = storedTarget
        activeProgress = 0
        setState(ImportState.Active(stored, PluginProtocol.JobState.VALIDATING, 0))
        requestSent = true
        session.resumeJob(stored, callbacks)
    }

    /**
     * Queues the single job for [url]. True means preflight was scheduled, not that the plugin
     * accepted it. A non-writable target is rejected asynchronously before any IPC start.
     */
    fun start(url: String): Boolean {
        if (mutableState.value is ImportState.Active || !capable) return false
        val source = url.trim()
        if (!isPlausibleUrl(source)) {
            setState(ImportState.Failed(PluginProtocol.ErrorCode.INVALID_INPUT))
            return false
        }
        val target = currentTarget() ?: run {
            setState(ImportState.Failed(ImportErrorCode.NO_TARGET))
            return false
        }
        val jobId = UUID.randomUUID().toString()
        activeJobId = jobId
        activeTarget = target
        activeProgress = 0
        setState(ImportState.Active(jobId, PluginProtocol.JobState.VALIDATING, 0))
        jobStore?.save(jobId, target.toString())
        // SAF provider calls can block just like the final copy. Reserve the job first
        // to reject duplicate taps, then perform the preflight off the UI thread.
        preflightTask = scope.launch {
            try {
                val writable = withContext(ioDispatcher) { writer.canWrite(target) }
                if (activeJobId != jobId) return@launch
                if (writable) {
                    requestSent = true
                    session.startJob(jobId, source, callbacks)
                }
                else {
                    endJob()
                    setState(ImportState.Failed(ImportErrorCode.TARGET_NOT_WRITABLE))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (activeJobId == jobId) {
                    endJob()
                    setState(ImportState.Failed(ImportErrorCode.WRITE_FAILED))
                }
            }
        }
        return true
    }

    /** Asks the plugin to cancel the active job; the terminal state arrives via status/result. */
    fun cancel() {
        val id = activeJobId ?: return
        if (deliveringJob == id) {
            deliveryTask?.cancel()
        } else if (!requestSent) {
            preflightTask?.cancel()
            endJob()
            setState(ImportState.Cancelled)
        } else session.cancel(id)
    }

    /** Releases the session binding. The controller keeps its last state. */
    fun close() {
        scope.cancel()
        clearJob()
        session.close()
    }

    private val callbacks = object : ImportJobCallbacks {

        override fun onAccepted(jobId: String) {
            if (jobId != activeJobId) return
            setState(ImportState.Active(jobId, PluginProtocol.JobState.VALIDATING, activeProgress))
        }

        override fun onStatus(jobId: String, state: Int, progress: Int) {
            if (jobId != activeJobId) return
            when {
                state == PluginProtocol.JobState.CANCELLED -> {
                    endJob()
                    setState(ImportState.Cancelled)
                }
                PluginProtocol.JobState.isKnown(state) -> {
                    activeProgress = PluginProtocol.Progress.advance(activeProgress, progress)
                    setState(ImportState.Active(jobId, state, activeProgress))
                }
                // Unknown state: fail closed instead of showing an undefined phase.
                else -> {
                    endJob()
                    setState(ImportState.Failed(PluginProtocol.ErrorCode.INTERNAL))
                }
            }
        }

        override fun onRejected(jobId: String, errorCode: Int) {
            if (jobId != activeJobId) return
            endJob()
            // BUSY and INVALID_INPUT land here as ordinary states, never as a crash.
            setState(ImportState.Failed(normalize(errorCode)))
        }

        override fun onResult(jobId: String, result: Bundle) {
            if (jobId != activeJobId || deliveringJob == jobId) return
            val target = activeTarget
            deliveringJob = jobId
            deliveryTask = scope.launch {
                try {
                    // Keep the job reserved until copy and ACK complete. In particular,
                    // duplicate JOB_RESULT messages must never copy the file twice.
                    val outcome = runInterruptible(ioDispatcher) { resolve(result, target) }
                    if (activeJobId == jobId) {
                        endJob()
                        setState(outcome)
                        if (outcome is ImportState.Succeeded) onLibraryChanged()
                    }
                } catch (cancelled: CancellationException) {
                    if (activeJobId == jobId) {
                        endJob()
                        setState(ImportState.Cancelled)
                    }
                    throw cancelled
                } catch (_: Exception) {
                    if (activeJobId == jobId) {
                        endJob()
                        setState(ImportState.Failed(ImportErrorCode.WRITE_FAILED))
                    }
                } finally {
                    runCatching { session.acknowledgeResult(jobId) }
                    if (deliveringJob == jobId) deliveringJob = null
                }
            }
        }

        override fun onUnavailable(jobId: String) {
            if (jobId != activeJobId) return
            endJob()
            setState(ImportState.Failed(ImportErrorCode.PLUGIN_UNREACHABLE))
        }

        override fun onTimeout(jobId: String) {
            if (jobId != activeJobId) return
            endJob()
            setState(ImportState.Failed(PluginProtocol.ErrorCode.TIMEOUT))
        }
    }

    /** Maps a terminal `JOB_RESULT` to a state; a success without a usable payload is a failure. */
    private fun resolve(result: Bundle, target: Uri?): ImportState =
        when (result.getInt(PluginProtocol.KEY_STATE, PluginProtocol.JobState.FAILED)) {
            PluginProtocol.JobState.SUCCEEDED -> deliver(result, target)
            PluginProtocol.JobState.CANCELLED -> ImportState.Cancelled
            else -> ImportState.Failed(
                normalize(result.getInt(PluginProtocol.KEY_ERROR, PluginProtocol.ErrorCode.NONE)))
        }

    /** Writes the delivered file into the fixed target; only a complete copy reports success. */
    private fun deliver(result: Bundle, target: Uri?): ImportState {
        val source = result.getString(PluginProtocol.KEY_RESULT_URI)
        if (target == null || source.isNullOrBlank()) {
            return ImportState.Failed(PluginProtocol.ErrorCode.INTERNAL)
        }
        val name = result.getString(PluginProtocol.KEY_FILE_NAME)
            ?: ImportFileNames.FALLBACK_BASE_NAME
        return when (val written =
            writer.write(target, Uri.parse(source), name, ImportFileNames.DEFAULT_MIME_TYPE)) {
            is WriteResult.Success -> ImportState.Succeeded(written.displayName)
            is WriteResult.Failure -> ImportState.Failed(ImportErrorCode.WRITE_FAILED)
        }
    }

    private fun setState(next: ImportState) {
        mutableState.value = next
        mutableCanStart.value = capable && next !is ImportState.Active &&
            next !is ImportState.Unavailable
    }

    private fun clearJob() {
        activeJobId = null
        activeTarget = null
        activeProgress = 0
        requestSent = false
    }

    /** Ends the active job and forgets the persisted id; every terminal state runs through here. */
    private fun endJob() {
        clearJob()
        jobStore?.clear()
    }

    companion object {

        /** Unknown plugin codes collapse to the generic [PluginProtocol.ErrorCode.INTERNAL]. */
        private fun normalize(errorCode: Int): Int =
            if (PluginProtocol.ErrorCode.isValid(errorCode) &&
                errorCode != PluginProtocol.ErrorCode.NONE) errorCode
            else PluginProtocol.ErrorCode.INTERNAL

        /** Minimal plausibility only: non-empty and an `http`/`https` scheme. The real source
         *  validation (supported source, reachability, media type) belongs to the plugin and is
         *  intentionally not duplicated here.
         */
        fun isPlausibleUrl(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            val scheme = Uri.parse(trimmed).scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            return trimmed.length > scheme.length + "://".length
        }
    }
}
