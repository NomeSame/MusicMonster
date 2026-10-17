package com.nomesame.musicmonster

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.plugin.ImportJobCallbacks
import com.nomesame.musicmonster.plugin.ImportJobClient
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [ImportJobClient] against a real [Messenger] and a fake
 * `bindService` (the [PluginRegistryTest] pattern): discovery/signature fixtures stand in for
 * the plugin package, so the client's binding, timeout, generation guard and ACK behaviour are
 * observable without a device.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ImportJobClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packages = shadowOf(context.packageManager)
    private val intent = Intent(PluginProtocol.BIND_ACTION)
    private val signer = Signature("010203")
    private val url = "https://example.test/watch?v=abc"

    @Before fun setup() {
        val host = context.packageManager.getPackageInfo(context.packageName, 0)
        host.signatures = arrayOf(signer)
        packages.installPackage(host)
    }

    private fun candidate(name: String = "test.plugin"): ServiceInfo {
        val app = ApplicationInfo().apply { packageName = name; enabled = true }
        packages.installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = app
            signatures = arrayOf(signer)
        })
        return ServiceInfo().apply {
            packageName = name
            this.name = "$name.PluginService"
            applicationInfo = app
            enabled = true
            exported = true
            permission = PluginProtocol.PERMISSION
            metaData = Bundle().apply { putInt(PluginProtocol.META_VERSION, PluginProtocol.VERSION) }
        }
    }

    private fun registerValidPlugin(): ComponentName {
        val service = candidate()
        packages.setResolveInfosForIntent(intent, listOf(ResolveInfo().apply { serviceInfo = service }))
        return ComponentName(service.packageName, service.name)
    }

    private fun removePlugins() = packages.setResolveInfosForIntent(intent, emptyList())

    private class BindingContext(base: Context) : ContextWrapper(base) {
        var connection: ServiceConnection? = null
        var target: Intent? = null
        var unbinds = 0
        var bindResult = true
        var denied = false
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            if (denied) throw SecurityException("Fixture denied")
            target = intent
            connection = conn
            return bindResult
        }
        override fun unbindService(conn: ServiceConnection) { unbinds++ }
    }

    private data class Received(val what: Int, val obj: Any?, val replyTo: Messenger?)

    /** Records every message it receives and optionally answers it. */
    private class Server {
        val received = mutableListOf<Received>()
        var onMessage: (Message) -> Unit = {}
        val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            // Same-process Messenger bypasses Binder marshalling. Exercise the actual
            // wire boundary too: a bare String in Message.obj crashes remotely.
            val parcel = Parcel.obtain()
            try { message.writeToParcel(parcel, 0) } finally { parcel.recycle() }
            received += Received(message.what, message.obj, message.replyTo)
            onMessage(message)
            true
        })

        fun start(): Received = received.first { it.what == PluginProtocol.JOB_START }
        fun count(what: Int) = received.count { it.what == what }
    }

    private class RecordingCallbacks : ImportJobCallbacks {
        val events = mutableListOf<String>()
        var result: Bundle? = null
        override fun onAccepted(jobId: String) { events += "accepted:$jobId" }
        override fun onRejected(jobId: String, errorCode: Int) { events += "rejected:$jobId:$errorCode" }
        override fun onStatus(jobId: String, state: Int, progress: Int) {
            events += "status:$jobId:$state:$progress"
        }
        override fun onResult(jobId: String, result: Bundle) {
            this.result = result
            events += "result:$jobId"
        }
        override fun onUnavailable(jobId: String) { events += "unavailable:$jobId" }
        override fun onTimeout(jobId: String) { events += "timeout:$jobId" }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun startSendsJobStartBundleAndStreamsReplies() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.startJob("job-1", url, callbacks)
            assertEquals(component, binding.target?.component)
            binding.connection!!.onServiceConnected(component, server.messenger.binder)
            idle()
            val start = server.start()
            val payload = start.obj as Bundle
            assertEquals("job-1", payload.getString(PluginProtocol.KEY_JOB_ID))
            assertEquals(url, payload.getString(PluginProtocol.KEY_URL))
            val reply = requireNotNull(start.replyTo)
            reply.send(Message.obtain(null, PluginProtocol.JOB_REPLY, PluginProtocol.JOB_ACCEPTED, 0)
                .apply { obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, "job-1") } })
            idle()
            reply.send(Message.obtain(null, PluginProtocol.JOB_STATUS).apply {
                obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, "job-1") }
                arg1 = PluginProtocol.JobState.DOWNLOADING
                arg2 = 42
            })
            idle()
            reply.send(Message.obtain(null, PluginProtocol.JOB_RESULT).apply {
                obj = Bundle().apply {
                    putString(PluginProtocol.KEY_JOB_ID, "job-1")
                    putInt(PluginProtocol.KEY_STATE, PluginProtocol.JobState.SUCCEEDED)
                }
            })
            idle()
            assertEquals(listOf("accepted:job-1", "status:job-1:2:42", "result:job-1"), callbacks.events)
            assertEquals(0, binding.unbinds)
        }
        assertEquals(1, binding.unbinds)
    }

    @Test fun missingPluginReportsUnavailableWithoutBinding() {
        removePlugins()
        val binding = BindingContext(context)
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.startJob("job-1", url, callbacks)
        }
        assertNull(binding.target)
        assertEquals(listOf("unavailable:job-1"), callbacks.events)
    }

    @Test fun silentPluginTimesOutAndUnbinds() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.startJob("job-1", url, callbacks)
            binding.connection!!.onServiceConnected(component, server.messenger.binder)
            idle()
            assertTrue(callbacks.events.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PluginProtocol.TIMEOUT_MS))
            assertEquals(listOf("timeout:job-1"), callbacks.events)
            assertEquals(1, binding.unbinds)
        }
    }

    @Test fun removedPluginAfterConnectIsRecheckedBeforeJobStart() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.startJob("job-1", url, callbacks)
            removePlugins()
            binding.connection!!.onServiceConnected(component, server.messenger.binder)
            idle()
            assertEquals(0, server.count(PluginProtocol.JOB_START))
            assertEquals(listOf("unavailable:job-1"), callbacks.events)
        }
    }

    @Test fun lateRepliesAfterCloseAreDropped() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        val client = ImportJobClient(binding)
        client.startJob("job-1", url, callbacks)
        binding.connection!!.onServiceConnected(component, server.messenger.binder)
        idle()
        val reply = requireNotNull(server.start().replyTo)
        client.close()
        reply.send(Message.obtain(null, PluginProtocol.JOB_STATUS).apply {
            obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, "job-1") }
            arg1 = PluginProtocol.JobState.CONVERTING
            arg2 = 80
        })
        idle()
        assertTrue(callbacks.events.isEmpty())
        assertEquals(1, binding.unbinds)
    }

    @Test fun rejectionIsDeliveredAndClientStaysUsable() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.startJob("job-1", url, callbacks)
            binding.connection!!.onServiceConnected(component, server.messenger.binder)
            idle()
            requireNotNull(server.start().replyTo).send(
                Message.obtain(null, PluginProtocol.JOB_REPLY, PluginProtocol.JOB_REJECTED,
                    PluginProtocol.ErrorCode.BUSY).apply { obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, "job-1") } })
            idle()
            assertEquals(listOf("rejected:job-1:10"), callbacks.events)
            val second = Server()
            client.startJob("job-2", url, callbacks)
            binding.connection!!.onServiceConnected(component, second.messenger.binder)
            idle()
            assertEquals("job-2", (second.start().obj as Bundle).getString(PluginProtocol.KEY_JOB_ID))
        }
    }

    @Test fun cancelQueryAndAckReachThePlugin() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.startJob("job-1", url, callbacks)
            binding.connection!!.onServiceConnected(component, server.messenger.binder)
            idle()
            client.cancel("job-1")
            client.query("job-1")
            client.acknowledgeResult("job-1")
            idle()
            assertEquals(1, server.count(PluginProtocol.JOB_CANCEL))
            val query = server.received.first { it.what == PluginProtocol.JOB_QUERY }
            assertEquals("job-1", (query.obj as Bundle).getString(PluginProtocol.KEY_JOB_ID))
            requireNotNull(query.replyTo)
            assertEquals(1, server.count(PluginProtocol.JOB_RESULT_ACK))
            // A message for an unknown job is never sent.
            client.cancel("other")
            idle()
            assertEquals(1, server.count(PluginProtocol.JOB_CANCEL))
        }
    }

    @Test fun resumeSendsJobQueryWithoutStartingAJob() {
        val component = registerValidPlugin()
        val binding = BindingContext(context)
        val server = Server()
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.resumeJob("job-9", callbacks)
            assertEquals(component, binding.target?.component)
            binding.connection!!.onServiceConnected(component, server.messenger.binder)
            idle()
            assertEquals(0, server.count(PluginProtocol.JOB_START))
            val query = server.received.first { it.what == PluginProtocol.JOB_QUERY }
            assertEquals("job-9", (query.obj as Bundle).getString(PluginProtocol.KEY_JOB_ID))
            val reply = requireNotNull(query.replyTo)
            reply.send(Message.obtain(null, PluginProtocol.JOB_STATUS).apply {
                obj = Bundle().apply { putString(PluginProtocol.KEY_JOB_ID, "job-9") }
                arg1 = PluginProtocol.JobState.CONVERTING
                arg2 = 80
            })
            idle()
            reply.send(Message.obtain(null, PluginProtocol.JOB_RESULT).apply {
                obj = Bundle().apply {
                    putString(PluginProtocol.KEY_JOB_ID, "job-9")
                    putInt(PluginProtocol.KEY_STATE, PluginProtocol.JobState.SUCCEEDED)
                }
            })
            idle()
            assertEquals(listOf("status:job-9:3:80", "result:job-9"), callbacks.events)
            assertEquals(0, binding.unbinds)
        }
        assertEquals(1, binding.unbinds)
    }

    @Test fun resumeWithoutPluginReportsUnavailable() {
        removePlugins()
        val binding = BindingContext(context)
        val callbacks = RecordingCallbacks()
        ImportJobClient(binding).use { client ->
            client.resumeJob("job-9", callbacks)
        }
        assertEquals(listOf("unavailable:job-9"), callbacks.events)
    }
}
