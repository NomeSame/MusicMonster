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
 */
class PluginProbe(context: Context) : AutoCloseable {
    enum class Result { READY, UNAVAILABLE, INCOMPATIBLE, FAILED, TIMEOUT }

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var callback: ((Result) -> Unit)? = null
    private var replyHandler: Handler? = null
    private var generation = 0L
    private val timeout = Runnable { finish(Result.TIMEOUT) }

    fun probe(component: ComponentName, onResult: (Result) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        val attempt = generation
        callback = onResult
        if (component !in PluginRegistry(app).available()) {
            finish(Result.UNAVAILABLE)
            return
        }
        val receiver = Handler(Looper.getMainLooper()) { message ->
            if (attempt == generation && message.what == PluginProtocol.HELLO_REPLY) {
                finish(if (PluginProtocol.accepts(message.arg1) &&
                    message.arg2 == PluginProtocol.READY) Result.READY else Result.INCOMPATIBLE)
            }
            true
        }
        replyHandler = receiver
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                if (component !in PluginRegistry(app).available()) {
                    finish(Result.UNAVAILABLE)
                    return
                }
                try {
                    Messenger(binder).send(Message.obtain(null, PluginProtocol.HELLO).apply {
                        arg1 = PluginProtocol.VERSION
                        replyTo = Messenger(receiver)
                    })
                } catch (_: RemoteException) { finish(Result.FAILED) }
            }
            override fun onServiceDisconnected(name: ComponentName) { failed() }
            override fun onNullBinding(name: ComponentName) { failed() }
            override fun onBindingDied(name: ComponentName) { failed() }
            private fun failed() { if (connection === this) finish(Result.FAILED) }
        }
        connection = serviceConnection
        try {
            if (!app.bindService(Intent(PluginProtocol.BIND_ACTION).setComponent(component),
                    serviceConnection, Context.BIND_AUTO_CREATE)) {
                finish(Result.UNAVAILABLE)
            } else if (connection === serviceConnection) {
                handler.postDelayed(timeout, PluginProtocol.TIMEOUT_MS)
            }
        } catch (_: RuntimeException) { finish(Result.FAILED) }
    }

    private fun finish(result: Result) {
        val notify = callback
        close()
        notify?.invoke(result)
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        handler.removeCallbacks(timeout)
        replyHandler?.removeCallbacksAndMessages(null)
        replyHandler = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        callback = null
    }
}
