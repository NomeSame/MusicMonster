package com.nomesame.musicmonster.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.nomesame.musicmonster.plugin.api.PluginProtocol

/**
 * Callbacks from an [ImportJobSession] to the state layer. Every method runs on the
 * main thread; job ids always name the job the session was started for (the state
 * layer still re-checks the id, since a late reply must never complete a newer job).
 */
interface ImportJobCallbacks {

    /** `JOB_REPLY` = accepted. */
    fun onAccepted(jobId: String)

    /** `JOB_REPLY` = rejected; [errorCode] is a [PluginProtocol.ErrorCode]. */
    fun onRejected(jobId: String, errorCode: Int)

    /** `JOB_STATUS` snapshot: [state] is a [PluginProtocol.JobState], [progress] 0–100. */
    fun onStatus(jobId: String, state: Int, progress: Int)

    /** terminal `JOB_RESULT`; [result] is the raw bundle (job id, state, error, optional uri/name). */
    fun onResult(jobId: String, result: Bundle)

    /** The plugin service could not be used (no plugin, bind failure, dead binder, send error). */
    fun onUnavailable(jobId: String)

    /** No `JOB_REPLY` arrived within [PluginProtocol.TIMEOUT_MS]. */
    fun onTimeout(jobId: String)
}

/**
 * Main-thread host↔plugin job session. The state layer depends on this interface so its
 * orchestration is testable without a real binder; [ImportJobClient] is the production
 * implementation.
 */
interface ImportJobSession : AutoCloseable {

    /** Binds, (re-)checks discovery, sends `JOB_START` with a fresh `Bundle`, then streams
     *  replies to [callbacks]. Exactly one job is tracked; a new call replaces the previous one.
     */
    fun startJob(jobId: String, url: String, callbacks: ImportJobCallbacks)

    /** Binds (if needed) and sends `JOB_QUERY` for [jobId] to re-attach after a host restart;
     *  the resulting status/result stream arrives on [callbacks]. It never starts a job, so a
     *  repeated call after a UI/process restart cannot spawn a second download (DECISIONS.md §6).
     */
    fun resumeJob(jobId: String, callbacks: ImportJobCallbacks)

    /** Sends `JOB_CANCEL` for [jobId]; a no-op in a terminal state (plugin decides). */
    fun cancel(jobId: String)

    /** Sends `JOB_QUERY` for [jobId] (rebind snapshot). Must never start a job. */
    fun query(jobId: String)

    /** Sends `JOB_RESULT_ACK` for [jobId] so the plugin can revoke the grant/drop the temp file. */
    fun acknowledgeResult(jobId: String)
}

/**
 * Binds to the (single, discovery-checked) plugin service and runs one import job over the
 * Messenger v1 job contract (private-plugin/DECISIONS.md §5/§6/§8).
 *
 * Layer: **Integration/Adapter** — the only place that touches `Messenger`/`Bundle` for jobs;
 * it holds no domain state and never mutates playback. Main-thread only, [close] releases the
 * binding; replies for a closed/replaced session are dropped by a generation guard.
 *
 * A job session itself never reacts to `JOB_QUERY`: it forwards the resulting status/result
 * and never starts a second job from a query.
 */
class ImportJobClient(context: Context) : ImportJobSession {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val registry = PluginRegistry(context)
    private var connection: ServiceConnection? = null
    private var messenger: Messenger? = null
    private var callbacks: ImportJobCallbacks? = null
    private var replyHandler: Handler? = null
    private var replyMessenger: Messenger? = null
    private var activeJobId: String? = null
    private var generation = 0L
    private val replyTimeout = Runnable { fail { callbacks, jobId -> callbacks.onTimeout(jobId) } }

    override fun startJob(jobId: String, url: String, callbacks: ImportJobCallbacks) {
        open(jobId, callbacks) { target ->
            target.send(Message.obtain(null, PluginProtocol.JOB_START).apply {
                obj = Bundle().apply {
                    putString(PluginProtocol.KEY_JOB_ID, jobId)
                    putString(PluginProtocol.KEY_URL, url)
                }
                replyTo = replyMessenger
            })
        }
    }

    override fun resumeJob(jobId: String, callbacks: ImportJobCallbacks) {
        open(jobId, callbacks) { target ->
            target.send(Message.obtain(null, PluginProtocol.JOB_QUERY).apply {
                obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, jobId) }
                replyTo = replyMessenger
            })
        }
    }

    /** Shared binding for [startJob]/[resumeJob]: reset the previous attempt, re-check discovery
     *  (also after connect), send exactly one request and arm the reply timeout.
     */
    private fun open(jobId: String, callbacks: ImportJobCallbacks, send: (Messenger) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        reset()
        val attempt = generation
        this.callbacks = callbacks
        activeJobId = jobId
        val component = registry.available().firstOrNull() ?: run { endUnavailable(); return }
        val receiver = Handler(Looper.getMainLooper()) { message ->
            if (attempt == generation) dispatch(message)
            true
        }
        replyHandler = receiver
        replyMessenger = Messenger(receiver)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                // Re-check after the bind: the package can be removed between pre-check and bind.
                if (component !in registry.available()) {
                    endUnavailable()
                    return
                }
                val target = Messenger(binder)
                messenger = target
                try {
                    send(target)
                } catch (_: RemoteException) { endUnavailable() }
            }
            override fun onServiceDisconnected(name: ComponentName) { failed() }
            override fun onNullBinding(name: ComponentName) { failed() }
            override fun onBindingDied(name: ComponentName) { failed() }
            private fun failed() { if (connection === this) endUnavailable() }
        }
        connection = serviceConnection
        try {
            if (!app.bindService(Intent(PluginProtocol.BIND_ACTION).setComponent(component),
                    serviceConnection, Context.BIND_AUTO_CREATE)) {
                endUnavailable()
            } else if (connection === serviceConnection) {
                handler.postDelayed(replyTimeout, PluginProtocol.TIMEOUT_MS)
            }
        } catch (_: RuntimeException) { endUnavailable() }
    }

    override fun cancel(jobId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (jobId != activeJobId) return
        if (!send(Message.obtain(null, PluginProtocol.JOB_CANCEL).apply { obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, jobId) } })) {
            endUnavailable()
        }
    }

    override fun query(jobId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (jobId != activeJobId) return
        send(Message.obtain(null, PluginProtocol.JOB_QUERY).apply {
            obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, jobId) }
            replyTo = replyMessenger
        })
    }

    override fun acknowledgeResult(jobId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (jobId != activeJobId) return
        send(Message.obtain(null, PluginProtocol.JOB_RESULT_ACK).apply { obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, jobId) } })
    }

    /** Routes only the job messages the host expects; anything else (HELLO/CAPS/unknown) is
     *  ignored, and a reply for a different job id can never complete this session.
     */
    private fun dispatch(message: Message) {
        when (message.what) {
            PluginProtocol.JOB_REPLY -> {
                val jobId = (message.obj as? Bundle)?.getString(PluginProtocol.KEY_JOB_ID) ?: return
                if (jobId != activeJobId) return
                handler.removeCallbacks(replyTimeout)
                if (message.arg1 == PluginProtocol.JOB_ACCEPTED) {
                    callbacks?.onAccepted(jobId)
                } else {
                    callbacks?.onRejected(jobId, message.arg2)
                }
            }
            PluginProtocol.JOB_STATUS -> {
                val jobId = (message.obj as? Bundle)?.getString(PluginProtocol.KEY_JOB_ID) ?: return
                if (jobId != activeJobId) return
                // A snapshot also answers a resume query, so it settles the reply timeout too.
                handler.removeCallbacks(replyTimeout)
                callbacks?.onStatus(jobId, message.arg1, message.arg2)
            }
            PluginProtocol.JOB_RESULT -> {
                val bundle = message.obj as? Bundle ?: return
                val jobId = bundle.getString(PluginProtocol.KEY_JOB_ID) ?: return
                if (jobId != activeJobId) return
                handler.removeCallbacks(replyTimeout)
                callbacks?.onResult(jobId, bundle)
            }
        }
    }

    private fun send(message: Message): Boolean {
        val target = messenger ?: return false
        return try {
            target.send(message)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    /** Notifies the session's callbacks with [action] (job id captured first), then releases
     *  the binding exactly once. Nothing is delivered when no job is active.
     */
    private inline fun fail(action: (ImportJobCallbacks, String) -> Unit) {
        val notify = callbacks
        val jobId = activeJobId
        reset()
        if (notify != null && jobId != null) action(notify, jobId)
    }

    private fun endUnavailable() = fail { callbacks, jobId -> callbacks.onUnavailable(jobId) }

    /** Unbinds and invalidates the current attempt; replies arriving afterwards are dropped. */
    private fun reset() {
        generation++
        handler.removeCallbacks(replyTimeout)
        replyHandler?.removeCallbacksAndMessages(null)
        replyHandler = null
        replyMessenger = null
        messenger = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        activeJobId = null
        callbacks = null
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        reset()
    }
}
