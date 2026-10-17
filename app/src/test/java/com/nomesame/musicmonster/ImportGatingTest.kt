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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.ActiveImportJobStore
import com.nomesame.musicmonster.data.ImportFileWriter
import com.nomesame.musicmonster.data.WriteResult
import com.nomesame.musicmonster.plugin.ImportJobCallbacks
import com.nomesame.musicmonster.plugin.ImportJobSession
import com.nomesame.musicmonster.plugin.PluginProbe
import com.nomesame.musicmonster.plugin.PluginRegistry
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import com.nomesame.musicmonster.state.ImportCapabilityCoordinator
import com.nomesame.musicmonster.state.ImportController
import com.nomesame.musicmonster.state.ImportState
import com.nomesame.musicmonster.state.entryVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric gating tests: the capability negotiation (real [PluginProbe] over a fake binding,
 * like [PluginRegistryTest]) drives the [ImportController] through [ImportCapabilityCoordinator].
 * No plugin / HELLO-only / incompatible ⇒ import stays [ImportState.Unavailable] and a start is
 * refused; a CAP_IMPORT reply unlocks the entry. Also pins the single-job rule and the
 * reconnect (JOB_QUERY) path with a fake session and store.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ImportGatingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packages = shadowOf(context.packageManager)
    private val intent = Intent(PluginProtocol.BIND_ACTION)
    private val signer = Signature("010203")
    private val valid = "https://example.test/watch?v=abc"

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

    private fun register(vararg services: ServiceInfo) {
        packages.setResolveInfosForIntent(intent, services.map { ResolveInfo().apply { serviceInfo = it } })
    }

    /** Fake `bindService`: records the connection so the test can drive `onServiceConnected`. */
    private class BindingContext(base: Context) : ContextWrapper(base) {
        var connection: ServiceConnection? = null
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return true
        }
        override fun unbindService(conn: ServiceConnection) { unbinds++ }
    }

    private class FakeSession : ImportJobSession {
        var callbacks: ImportJobCallbacks? = null
        var starts = 0
        var resumes = 0
        val queries = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        val acks = mutableListOf<String>()

        override fun startJob(jobId: String, url: String, callbacks: ImportJobCallbacks) {
            starts++; this.callbacks = callbacks
        }

        override fun resumeJob(jobId: String, callbacks: ImportJobCallbacks) {
            resumes++; this.callbacks = callbacks
        }

        override fun cancel(jobId: String) { cancels += jobId }
        override fun query(jobId: String) { queries += jobId }
        override fun acknowledgeResult(jobId: String) { acks += jobId }
        override fun close() {}
    }

    private class FakeWriter : ImportFileWriter {
        var writtenTarget: Uri? = null
        override fun canWrite(treeUri: Uri): Boolean = true
        override fun write(treeUri: Uri, sourceUri: Uri, baseName: String, mimeType: String): WriteResult {
            writtenTarget = treeUri
            return WriteResult.Success(Uri.parse("content://library/$baseName"), baseName)
        }
    }

    private class FakeStore(var id: String? = null, var target: String? = "content://original/library") : ActiveImportJobStore {
        override fun load(): String? = id
        override fun loadTarget(): String? = target
        override fun save(jobId: String, targetUri: String) { id = jobId; target = targetUri }
        override fun clear() { id = null; target = null }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() = repeat(5) { idle() }

    private fun capabilityServer(
        caps: Int = PluginProtocol.CAP_IMPORT,
        answerCaps: Boolean = true,
        helloVersion: Int = PluginProtocol.VERSION,
    ): Messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        when (request.what) {
            PluginProtocol.HELLO -> request.replyTo?.send(
                Message.obtain(null, PluginProtocol.HELLO_REPLY, helloVersion, PluginProtocol.READY))
            PluginProtocol.CAPS -> if (answerCaps) {
                request.replyTo?.send(Message.obtain(null, PluginProtocol.CAPS_REPLY, caps, 0))
            }
        }
        true
    })

    private val session = FakeSession()
    private var refreshes = 0

    private fun controller(store: ActiveImportJobStore? = null, writer: FakeWriter = FakeWriter()) = ImportController(
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        session = session,
        writer = writer,
        currentTarget = { Uri.parse("content://tree/library") },
        onLibraryChanged = { refreshes++ },
        jobStore = store,
    )

    private fun resultBundle(jobId: String, state: Int, error: Int = PluginProtocol.ErrorCode.NONE) =
        Bundle().apply {
            putString(PluginProtocol.KEY_JOB_ID, jobId)
            putInt(PluginProtocol.KEY_STATE, state)
            putInt(PluginProtocol.KEY_ERROR, error)
            putString(PluginProtocol.KEY_RESULT_URI, "content://plugin/tmp/result.mp3")
            putString(PluginProtocol.KEY_FILE_NAME, "Song.mp3")
        }

    private fun coordinator(controller: ImportController, binding: BindingContext) =
        ImportCapabilityCoordinator(
            controller = controller,
            components = { PluginRegistry(binding).available() },
            newProbe = { PluginProbe(binding) },
        )

    @Test fun withoutPluginImportStaysUnavailableAndStartIsRefused() {
        register()
        val controller = controller()
        coordinator(controller, BindingContext(context)).refresh()
        assertTrue(controller.state.value is ImportState.Unavailable)
        assertFalse("entry must stay hidden without a capable plugin", controller.state.value.entryVisible)
        assertFalse(controller.canStart.value)
        assertFalse(controller.start(valid))
        assertEquals(0, session.starts)
    }

    @Test fun importCapabilityUnlocksTheEntryAndAllowsExactlyOneJob() {
        val service = candidate()
        register(service)
        val binding = BindingContext(context)
        val controller = controller()
        val component = ComponentName(service.packageName, service.name)
        coordinator(controller, binding).refresh()
        binding.connection!!.onServiceConnected(component, capabilityServer().binder)
        settle()

        assertTrue(controller.state.value is ImportState.Idle)
        assertTrue("entry must be visible with CAP_IMPORT", controller.state.value.entryVisible)
        assertTrue(controller.canStart.value)
        assertTrue(controller.start(valid))
        assertEquals(1, session.starts)
        // A second start while active is refused without touching the session.
        assertFalse(controller.start(valid))
        assertEquals(1, session.starts)
    }

    @Test fun helloOnlyPluginStaysHiddenAfterTheCapabilityTimeout() {
        val service = candidate()
        register(service)
        val binding = BindingContext(context)
        val controller = controller()
        val component = ComponentName(service.packageName, service.name)
        coordinator(controller, binding).refresh()
        binding.connection!!.onServiceConnected(component, capabilityServer(answerCaps = false).binder)
        settle()
        // While waiting the entry is already off (fail-closed), and stays off after the timeout.
        assertTrue(controller.state.value is ImportState.Unavailable)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PluginProbe.CAPABILITY_TIMEOUT_MS + 20))
        assertTrue(controller.state.value is ImportState.Unavailable)
        assertFalse("HELLO-only must keep the entry hidden", controller.state.value.entryVisible)
        assertFalse(controller.canStart.value)
        assertFalse(controller.start(valid))
        assertEquals(0, session.starts)
    }

    @Test fun incompatiblePluginVersionKeepsImportUnavailable() {
        val service = candidate()
        register(service)
        val binding = BindingContext(context)
        val controller = controller()
        val component = ComponentName(service.packageName, service.name)
        coordinator(controller, binding).refresh()
        binding.connection!!.onServiceConnected(component,
            capabilityServer(helloVersion = PluginProtocol.VERSION + 1).binder)
        settle()
        assertTrue(controller.state.value is ImportState.Unavailable)
        assertFalse(controller.state.value.entryVisible)
        assertFalse(controller.start(valid))
        assertEquals(0, session.starts)
    }

    @Test fun cancelForwardsTheActiveJobAndNeverStartsASecondOne() {
        val service = candidate()
        register(service)
        val binding = BindingContext(context)
        val controller = controller()
        val component = ComponentName(service.packageName, service.name)
        coordinator(controller, binding).refresh()
        binding.connection!!.onServiceConnected(component, capabilityServer().binder)
        settle()
        controller.start(valid)
        val job = (controller.state.value as ImportState.Active).jobId

        controller.cancel() // what MainViewModel.cancelImport() delegates to
        assertEquals(listOf(job), session.cancels)
        assertFalse(controller.start(valid))
        assertEquals(1, session.starts)
    }

    @Test fun libraryRefreshFiresOnceOnSuccessAndNeverOnFailure() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)

        controller.start(valid)
        val successJob = (controller.state.value as ImportState.Active).jobId
        session.callbacks!!.onResult(successJob, resultBundle(successJob, PluginProtocol.JobState.SUCCEEDED))
        assertEquals(ImportState.Succeeded("Song.mp3"), controller.state.value)
        assertEquals(1, refreshes)
        assertEquals(listOf(successJob), session.acks)

        controller.start(valid)
        val failJob = (controller.state.value as ImportState.Active).jobId
        session.callbacks!!.onResult(failJob, resultBundle(
            failJob, PluginProtocol.JobState.FAILED, PluginProtocol.ErrorCode.NETWORK))
        assertTrue(controller.state.value is ImportState.Failed)
        assertEquals("a failure must not refresh the library", 1, refreshes)
    }

    @Test fun reattachAfterProcessRestartQueriesTheStoredJobWithoutStartingOne() {
        val store = FakeStore(id = "job-7")
        val writer = FakeWriter()
        val controller = controller(store, writer)
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)

        controller.reattach()
        assertEquals(ImportState.Active("job-7", PluginProtocol.JobState.VALIDATING, 0), controller.state.value)
        assertEquals(1, session.resumes)
        assertEquals(0, session.starts)

        // A live in-memory job (config change) only asks for a fresh snapshot.
        controller.reattach()
        assertEquals(listOf("job-7"), session.queries)
        assertEquals(1, session.resumes)

        // A terminal result clears the persisted id.
        session.callbacks!!.onResult("job-7", resultBundle("job-7", PluginProtocol.JobState.SUCCEEDED))
        assertEquals(Uri.parse("content://original/library"), writer.writtenTarget)
        assertEquals(null, store.id)
        assertEquals(1, refreshes)
    }

    @Test fun legacyJobWithoutItsOriginalTargetCannotWriteIntoTheCurrentFolder() {
        val store = FakeStore(id = "legacy-job", target = null)
        val controller = controller(store)
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.reattach()
        assertTrue(controller.state.value is ImportState.Failed)
        assertEquals(0, session.resumes)
        assertEquals(null, store.id)
    }

    @Test fun reattachDoesNothingWithoutCapabilityOrStoredJob() {
        val store = FakeStore(id = "job-7")
        val controller = controller(store)
        controller.reattach() // no capability yet
        assertEquals(0, session.resumes)

        controller.onCapabilities(0) // HELLO-only => capability lost, persisted id dropped
        assertEquals(null, store.id)
        controller.reattach()
        assertEquals(0, session.resumes)
        assertTrue(controller.state.value is ImportState.Unavailable)
    }
}
