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
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.plugin.PluginProbe
import com.nomesame.musicmonster.plugin.PluginRegistry
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PluginRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packages = shadowOf(context.packageManager)
    private val intent = Intent(PluginProtocol.BIND_ACTION)
    private val signer = Signature("010203")

    @Before fun setup() {
        val host = context.packageManager.getPackageInfo(context.packageName, 0)
        host.signatures = arrayOf(signer)
        packages.installPackage(host)
    }

    private fun candidate(name: String = "test.plugin", signature: Signature = signer): ServiceInfo {
        val app = ApplicationInfo().apply { packageName = name; enabled = true }
        packages.installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = app
            signatures = arrayOf(signature)
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

    private fun register(vararg services: ServiceInfo) {
        packages.setResolveInfosForIntent(intent, services.map { service ->
            ResolveInfo().apply { serviceInfo = service }
        })
    }

    @Test fun absenceAndUntrustedPluginsAreUnavailable() {
        register()
        assertTrue(PluginRegistry(context).available().isEmpty())
        register(candidate(signature = Signature("040506")))
        assertTrue(PluginRegistry(context).available().isEmpty())
    }

    @Test fun validPluginsAreSortedUniqueAndRemovedOnNextLookup() {
        val b = candidate("test.b")
        val a = candidate("test.a")
        register(b, a, b)
        assertEquals(listOf(ComponentName(a.packageName, a.name), ComponentName(b.packageName, b.name)),
            PluginRegistry(context).available())
        register()
        assertTrue(PluginRegistry(context).available().isEmpty())
    }

    @Test fun rejectsDisabledPrivateUnprotectedAndIncompatibleEndpoints() {
        val mutations: List<(ServiceInfo) -> Unit> = listOf(
            { it.enabled = false }, { it.applicationInfo.enabled = false },
            { it.exported = false }, { it.permission = null },
            { it.permission = "android.permission.INTERNET" }, { it.metaData = null },
            { it.metaData.putInt(PluginProtocol.META_VERSION, 0) },
            { it.metaData.putInt(PluginProtocol.META_VERSION, Int.MAX_VALUE) },
            { it.metaData.putString(PluginProtocol.META_VERSION, "1") }
        )
        mutations.forEach { mutate ->
            val service = candidate()
            mutate(service)
            register(service)
            assertTrue(PluginRegistry(context).available().isEmpty())
        }
    }

    @Test fun unknownComponentNeverBindsAndReportsOnce() {
        register()
        val results = mutableListOf<PluginProbe.Result>()
        PluginProbe(context).use { probe ->
            probe.probe(ComponentName("unknown", "unknown.Service"), results::add)
        }
        assertEquals(listOf(PluginProbe.Result.UNAVAILABLE), results)
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test fun wireVersionFailsClosedAtBoundaries() {
        assertTrue(PluginProtocol.accepts(1))
        listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE).forEach {
            assertFalse(PluginProtocol.accepts(it))
        }
    }

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

    @Test fun handshakeUsesExplicitComponentAndCleansUpExactlyOnce() {
        val service = candidate()
        register(service)
        val component = ComponentName(service.packageName, service.name)
        val binding = BindingContext(context)
        val results = mutableListOf<PluginProbe.Result>()
        val server = Messenger(Handler(Looper.getMainLooper()) { request ->
            assertEquals(PluginProtocol.HELLO, request.what)
            assertEquals(1, request.arg1)
            request.replyTo.send(Message.obtain(null, PluginProtocol.HELLO_REPLY, 1, PluginProtocol.READY))
            true
        })
        PluginProbe(binding).use { probe ->
            probe.probe(component, results::add)
            assertEquals(component, binding.target?.component)
            binding.connection!!.onServiceConnected(component, server.binder)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertEquals(listOf(PluginProbe.Result.READY), results)
            assertEquals(1, binding.unbinds)
        }
        assertEquals(1, binding.unbinds)
    }

    @Test fun timeoutCloseAndLateRepliesCannotCompleteAnotherAttempt() {
        val service = candidate()
        register(service)
        val component = ComponentName(service.packageName, service.name)
        val binding = BindingContext(context)
        val results = mutableListOf<PluginProbe.Result>()
        var oldReply: Messenger? = null
        val server = Messenger(Handler(Looper.getMainLooper()) { oldReply = it.replyTo; true })
        PluginProbe(binding).use { probe ->
            probe.probe(component, results::add)
            binding.connection!!.onServiceConnected(component, server.binder)
            shadowOf(Looper.getMainLooper()).idle()
            probe.close()
            probe.probe(component, results::add)
            requireNotNull(oldReply).send(Message.obtain(null, PluginProtocol.HELLO_REPLY, 1, 0))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(results.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PluginProtocol.TIMEOUT_MS))
            assertEquals(listOf(PluginProbe.Result.TIMEOUT), results)
        }
        assertEquals(2, binding.unbinds)
    }

    @Test fun failedBindSecurityDenialAndDeadServiceTerminateWithoutWaiting() {
        val service = candidate()
        register(service)
        val component = ComponentName(service.packageName, service.name)
        val binding = BindingContext(context)
        val results = mutableListOf<PluginProbe.Result>()
        PluginProbe(binding).use { probe ->
            binding.bindResult = false
            probe.probe(component, results::add)
            binding.denied = true
            probe.probe(component, results::add)
            binding.denied = false
            binding.bindResult = true
            probe.probe(component, results::add)
            binding.connection!!.onServiceDisconnected(component)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        }
        assertEquals(listOf(PluginProbe.Result.UNAVAILABLE, PluginProbe.Result.FAILED,
            PluginProbe.Result.FAILED), results)
    }

    @Test fun removedPluginIsRecheckedBeforeHandshakeAndVersionMismatchFailsClosed() {
        val service = candidate()
        register(service)
        val component = ComponentName(service.packageName, service.name)
        val binding = BindingContext(context)
        val results = mutableListOf<PluginProbe.Result>()
        var requests = 0
        val server = Messenger(Handler(Looper.getMainLooper()) {
            requests++
            it.replyTo.send(Message.obtain(null, PluginProtocol.HELLO_REPLY, 2, 0))
            true
        })
        PluginProbe(binding).use { probe ->
            probe.probe(component, results::add)
            register()
            binding.connection!!.onServiceConnected(component, server.binder)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, requests)
            register(service)
            probe.probe(component, results::add)
            binding.connection!!.onServiceConnected(component, server.binder)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, requests)
        }
        assertEquals(listOf(PluginProbe.Result.UNAVAILABLE, PluginProbe.Result.INCOMPATIBLE), results)
    }
}
