package com.nomesame.musicmonster.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.nomesame.musicmonster.plugin.api.PluginProtocol

/** One-shot, main-thread handshake. Owner must close on lifecycle end; close cancels silently.
 * READY means protocol handshake only, never that downloading/importing is implemented.
 *
 * [probe] is the protocol-only handshake. [probeCapabilities] additionally negotiates
 * capabilities (DECISIONS.md §4): a READY plugin that ignores the additive CAPS request yields
 * READY with `capabilities == 0` — never an error, never an assumed capability, so a HELLO-only
 * plugin leaves the import UI off without affecting the player. Trust/capability are never
 * cached: call again for a fresh answer.
 */
class PluginProbe(context: Context) : AutoCloseable {
    enum class Result { READY, UNAVAILABLE, INCOMPATIBLE, FAILED, TIMEOUT }

    /** Outcome of [probeCapabilities]: negotiated [result] plus the CAPS_REPLY bitmask.
     *  [capabilities] is 0 whenever the plugin answered neither CAPS nor anything usable.
     */
    data class CapabilityResult(val result: Result, val capabilities: Int)

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var callback: ((Result) -> Unit)? = null
    private var capabilityCallback: ((CapabilityResult) -> Unit)? = null
    private var replyHandler: Handler? = null
    private var replyMessenger: Messenger? = null
    private var requestMessenger: Messenger? = null
    private var generation = 0L
    private val timeout = Runnable { end(Result.TIMEOUT, 0) }
    private val capabilityTimeout = Runnable { end(Result.READY, 0) }

    fun probe(component: ComponentName, onResult: (Result) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        val attempt = generation
        callback = onResult
        if (component !in PluginRegistry(app).available()) {
            end(Result.UNAVAILABLE, 0)
            return
        }
        bind(component, attempt) { message ->
            if (message.what == PluginProtocol.HELLO_REPLY) {
                end(if (PluginProtocol.accepts(message.arg1) &&
                    message.arg2 == PluginProtocol.READY) Result.READY else Result.INCOMPATIBLE, 0)
            }
        }
    }

    /** Handshake plus capability negotiation. After a READY HELLO_REPLY the probe sends CAPS;
     *  the CAPS_REPLY bitmask (`arg1`) is reported. A plugin that ignores CAPS (today's
     *  HELLO-only build) therefore yields `READY` with `capabilities == 0` after
     *  [CAPABILITY_TIMEOUT_MS] instead of hanging the flow.
     */
    fun probeCapabilities(component: ComponentName, onResult: (CapabilityResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        val attempt = generation
        capabilityCallback = onResult
        if (component !in PluginRegistry(app).available()) {
            end(Result.UNAVAILABLE, 0)
            return
        }
        bind(component, attempt) { message ->
            when (message.what) {
                PluginProtocol.HELLO_REPLY -> if (PluginProtocol.accepts(message.arg1) &&
                    message.arg2 == PluginProtocol.READY) requestCapabilities() else end(
                        Result.INCOMPATIBLE, 0)
                PluginProtocol.CAPS_REPLY -> end(Result.READY, message.arg1)
            }
        }
    }

    /** Sends CAPS over the live binding and swaps the HELLO timeout for the shorter
     *  capability timeout, so a silent plugin resolves to "no capabilities" quickly.
     */
    private fun requestCapabilities() {
        val messenger = requestMessenger ?: return
        val reply = replyMessenger ?: return
        try {
            messenger.send(Message.obtain(null, PluginProtocol.CAPS).apply { replyTo = reply })
        } catch (_: RemoteException) {
            end(Result.FAILED, 0)
            return
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(capabilityTimeout, CAPABILITY_TIMEOUT_MS)
    }

    /** Shared binding: re-checks discovery in [ServiceConnection.onServiceConnected] (the
     *  package may have vanished between the pre-check and the bind) and sends HELLO.
     */
    private fun bind(component: ComponentName, attempt: Long, onReply: (Message) -> Unit) {
        val receiver = Handler(Looper.getMainLooper()) { message ->
            if (attempt == generation) onReply(message)
            true
        }
        replyHandler = receiver
        replyMessenger = Messenger(receiver)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                if (component !in PluginRegistry(app).available()) {
                    end(Result.UNAVAILABLE, 0)
                    return
                }
                val messenger = Messenger(binder)
                requestMessenger = messenger
                try {
                    messenger.send(Message.obtain(null, PluginProtocol.HELLO).apply {
                        arg1 = PluginProtocol.VERSION
                        replyTo = replyMessenger
                    })
                } catch (_: RemoteException) { end(Result.FAILED, 0) }
            }
            override fun onServiceDisconnected(name: ComponentName) { failed() }
            override fun onNullBinding(name: ComponentName) { failed() }
            override fun onBindingDied(name: ComponentName) { failed() }
            private fun failed() { if (connection === this) end(Result.FAILED, 0) }
        }
        connection = serviceConnection
        try {
            if (!app.bindService(Intent(PluginProtocol.BIND_ACTION).setComponent(component),
                    serviceConnection, Context.BIND_AUTO_CREATE)) {
                end(Result.UNAVAILABLE, 0)
            } else if (connection === serviceConnection) {
                handler.postDelayed(timeout, PluginProtocol.TIMEOUT_MS)
            }
        } catch (_: RuntimeException) { end(Result.FAILED, 0) }
    }

    /** Notifies whichever single attempt is outstanding, then releases the binding. */
    private fun end(result: Result, capabilities: Int) {
        val notify = callback
        val notifyCapabilities = capabilityCallback
        close()
        notify?.invoke(result)
        notifyCapabilities?.invoke(CapabilityResult(result, capabilities))
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(capabilityTimeout)
        replyHandler?.removeCallbacksAndMessages(null)
        replyHandler = null
        replyMessenger = null
        requestMessenger = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        callback = null
        capabilityCallback = null
    }

    companion object {
        /** Own, deliberately short budget for a CAPS_REPLY after a READY handshake. A plugin
         *  that never acknowledges CAPS is treated as import-incapable, not as a failure.
         */
        const val CAPABILITY_TIMEOUT_MS = 1_000L
    }
}
