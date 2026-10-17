package com.nomesame.musicmonster

import android.net.Uri
import android.os.Bundle
import com.nomesame.musicmonster.data.ImportFileWriter
import com.nomesame.musicmonster.data.WriteResult
import com.nomesame.musicmonster.plugin.ImportJobCallbacks
import com.nomesame.musicmonster.plugin.ImportJobSession
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import com.nomesame.musicmonster.state.ImportController
import com.nomesame.musicmonster.state.ImportErrorCode
import com.nomesame.musicmonster.state.ImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [ImportController] with a fake [ImportJobSession] and a fake
 * [ImportFileWriter]: no binder, no SAF provider, no user data. The controller owns all import
 * rules (capability gate, single job, fixed target, ACK path), so the whole flow is observable
 * here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ImportControllerTest {

    private val valid = "https://example.test/watch?v=abc"
    private val other = "https://example.test/watch?v=other"

    private class FakeSession : ImportJobSession {
        val started = java.util.concurrent.CountDownLatch(1)
        val acknowledged = java.util.concurrent.CountDownLatch(1)
        var callbacks: ImportJobCallbacks? = null
        var starts = 0
        var resumes = 0
        var lastJobId: String? = null
        var lastUrl: String? = null
        val cancels = mutableListOf<String>()
        val acks = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var closed = false

        override fun startJob(jobId: String, url: String, callbacks: ImportJobCallbacks) {
            starts++
            lastJobId = jobId
            lastUrl = url
            this.callbacks = callbacks
            started.countDown()
        }

        override fun resumeJob(jobId: String, callbacks: ImportJobCallbacks) {
            resumes++
            lastJobId = jobId
            this.callbacks = callbacks
        }

        override fun cancel(jobId: String) { cancels += jobId }
        override fun query(jobId: String) { queries += jobId }
        override fun acknowledgeResult(jobId: String) { acks += jobId; acknowledged.countDown() }
        override fun close() { closed = true }
    }

    private class FakeWriter(
        var writable: Boolean = true,
        var result: WriteResult =
            WriteResult.Success(Uri.parse("content://library/Song.mp3"), "Song.mp3"),
    ) : ImportFileWriter {
        data class Call(val target: Uri, val source: Uri, val name: String, val mime: String)
        val writes = mutableListOf<Call>()

        override fun canWrite(treeUri: Uri): Boolean = writable

        override fun write(
            treeUri: Uri,
            sourceUri: Uri,
            baseName: String,
            mimeType: String,
        ): WriteResult {
            writes += Call(treeUri, sourceUri, baseName, mimeType)
            return result
        }
    }

    private val session = FakeSession()
    private var writer = FakeWriter()
    private var target: Uri? = Uri.parse("content://tree/library")
    private var refreshes = 0

    private fun controller() = ImportController(
        session = session,
        writer = writer,
        currentTarget = { target },
        onLibraryChanged = { refreshes++ },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    private fun resultBundle(
        jobId: String,
        state: Int,
        error: Int = PluginProtocol.ErrorCode.NONE,
        uri: String? = "content://plugin/tmp/result.mp3",
        name: String? = "Song.mp3",
    ) = Bundle().apply {
        putString(PluginProtocol.KEY_JOB_ID, jobId)
        putInt(PluginProtocol.KEY_STATE, state)
        putInt(PluginProtocol.KEY_ERROR, error)
        uri?.let { putString(PluginProtocol.KEY_RESULT_URI, it) }
        name?.let { putString(PluginProtocol.KEY_FILE_NAME, it) }
    }

    private fun activeJob(controller: ImportController): String =
        (controller.state.value as ImportState.Active).jobId

    // (a) capability missing
    @Test fun withoutImportCapabilityStateIsUnavailableAndStartIsRefused() {
        val controller = controller()
        assertTrue(controller.state.value is ImportState.Unavailable)
        assertFalse(controller.canStart.value)

        controller.onCapabilities(0)
        assertTrue(controller.state.value is ImportState.Unavailable)

        controller.onCapabilities(2) // unknown bit only, no CAP_IMPORT
        assertTrue(controller.state.value is ImportState.Unavailable)

        assertFalse(controller.start(valid))
        assertEquals(0, session.starts)
        assertFalse(controller.canStart.value)
    }

    @Test fun capabilityWithoutImportBitFailsClosed() {
        assertFalse(PluginProtocol.hasCapability(0, PluginProtocol.CAP_IMPORT))
        assertFalse(PluginProtocol.hasCapability(2, PluginProtocol.CAP_IMPORT))
        assertTrue(PluginProtocol.hasCapability(
            PluginProtocol.CAP_IMPORT or 2, PluginProtocol.CAP_IMPORT))
    }

    // (b) happy path with monotonic progress
    @Test fun happyPathIdleActiveSucceededWithMonotonicProgress() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        assertTrue(controller.state.value is ImportState.Idle)
        assertTrue(controller.canStart.value)

        assertTrue(controller.start(valid))
        val job = activeJob(controller)
        assertEquals(1, session.starts)
        assertEquals(valid, session.lastUrl)
        assertFalse(controller.canStart.value)

        val callbacks = requireNotNull(session.callbacks)
        callbacks.onAccepted(job)
        callbacks.onStatus(job, PluginProtocol.JobState.DOWNLOADING, 30)
        assertEquals(ImportState.Active(job, PluginProtocol.JobState.DOWNLOADING, 30), controller.state.value)
        callbacks.onStatus(job, PluginProtocol.JobState.DOWNLOADING, 50)
        // A regressing snapshot must never lower the reported progress.
        callbacks.onStatus(job, PluginProtocol.JobState.CONVERTING, 40)
        assertEquals(ImportState.Active(job, PluginProtocol.JobState.CONVERTING, 50), controller.state.value)

        callbacks.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED))
        assertEquals(ImportState.Succeeded("Song.mp3"), controller.state.value)
        assertEquals(1, refreshes)
        assertEquals(1, writer.writes.size)
        assertEquals(listOf(job), session.acks)
        assertTrue(controller.canStart.value)
    }

    // (c) exactly one job
    @Test fun secondStartWhileActiveIsRefusedWithoutTouchingTheSession() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        assertTrue(controller.start(valid))
        val job = activeJob(controller)

        assertFalse(controller.start(other))
        assertEquals(1, session.starts)
        assertEquals(ImportState.Active(job, PluginProtocol.JobState.VALIDATING, 0), controller.state.value)
    }

    // (d) rejection stays a clean state and leaves the client usable
    @Test fun busyAndInvalidInputRejectionsBecomeStatesAndClientStaysUsable() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)

        assertTrue(controller.start(valid))
        session.callbacks!!.onRejected(activeJob(controller), PluginProtocol.ErrorCode.BUSY)
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.BUSY), controller.state.value)
        assertTrue(controller.canStart.value)

        assertTrue(controller.start(valid))
        session.callbacks!!.onRejected(activeJob(controller), PluginProtocol.ErrorCode.INVALID_INPUT)
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.INVALID_INPUT), controller.state.value)

        assertTrue(controller.start(valid))
        assertEquals(3, session.starts)
    }

    // (e) success + writer success => Succeeded and ACK
    @Test fun successfulResultWritesFileAcksAndSignalsLibraryChange() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED))

        assertEquals(ImportState.Succeeded("Song.mp3"), controller.state.value)
        val write = writer.writes.single()
        assertEquals(target, write.target)
        assertEquals(Uri.parse("content://plugin/tmp/result.mp3"), write.source)
        assertEquals("Song.mp3", write.name)
        assertEquals("audio/mpeg", write.mime)
        assertEquals(listOf(job), session.acks)
        assertEquals(1, refreshes)
    }

    // (f) success + writer failure => failure, still ACK, no success
    @Test fun writeFailureIsAFailureThatStillAcknowledges() {
        writer.result = WriteResult.Failure(WriteResult.Reason.SOURCE_UNREADABLE)
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED))

        assertEquals(ImportState.Failed(ImportErrorCode.WRITE_FAILED), controller.state.value)
        assertEquals(listOf(job), session.acks)
        assertEquals(0, refreshes)
    }

    @Test fun successWithoutResultUriFailsButStillAcknowledges() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED, uri = null))

        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.INTERNAL), controller.state.value)
        assertEquals(listOf(job), session.acks)
        assertEquals(0, refreshes)
    }

    // (g) unwritable target => no job start
    @Test fun unwritableTargetPreventsTheJobStart() {
        writer.writable = false
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)

        assertTrue(controller.start(valid)) // Queued request; async preflight rejects before IPC.
        assertEquals(0, session.starts)
        assertEquals(ImportState.Failed(ImportErrorCode.TARGET_NOT_WRITABLE), controller.state.value)
    }

    @Test fun missingLibraryTreePreventsTheJobStart() {
        target = null
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)

        assertFalse(controller.start(valid))
        assertEquals(0, session.starts)
        assertEquals(ImportState.Failed(ImportErrorCode.NO_TARGET), controller.state.value)
    }

    // (h) CANCELLED status
    @Test fun cancelledStatusBecomesCancelledState() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onStatus(job, PluginProtocol.JobState.CANCELLED, 100)
        assertEquals(ImportState.Cancelled, controller.state.value)
    }

    @Test fun cancelRequestIsForwardedAndKeepsTheJobActiveUntilConfirmation() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        controller.cancel()
        assertEquals(listOf(job), session.cancels)
        assertTrue(controller.state.value is ImportState.Active)
    }

    // (i) the target URI is fixed at start
    @Test fun targetUriIsFixedAtStartAndLaterFolderChangeDoesNotRedirect() {
        val first = Uri.parse("content://tree/one")
        val second = Uri.parse("content://tree/two")
        target = first
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        target = second // the user switches folders while the job runs
        session.callbacks!!.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED))

        assertEquals(first, writer.writes.single().target)
    }

    // (j) unknown codes/states fail closed instead of crashing
    @Test fun unknownPluginErrorCodeBecomesGenericFailure() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onResult(job, resultBundle(
            job, PluginProtocol.JobState.FAILED, error = 999))
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.INTERNAL), controller.state.value)
        assertEquals(listOf(job), session.acks)
    }

    @Test fun unknownJobStatusFailsClosed() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onStatus(job, 4242, 10)
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.INTERNAL), controller.state.value)
    }

    @Test fun pluginReportedFailureKeepsItsErrorCodeAndAcks() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onResult(job, resultBundle(
            job, PluginProtocol.JobState.FAILED, error = PluginProtocol.ErrorCode.NETWORK))
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.NETWORK), controller.state.value)
        assertEquals(listOf(job), session.acks)
    }

    @Test fun timeoutAndUnavailableBecomeDistinguishableFailures() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)

        session.callbacks!!.onTimeout(job)
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.TIMEOUT), controller.state.value)

        controller.start(valid)
        session.callbacks!!.onUnavailable(activeJob(controller))
        assertEquals(ImportState.Failed(ImportErrorCode.PLUGIN_UNREACHABLE), controller.state.value)
    }

    @Test fun lateCallbacksForAFinishedJobAreIgnored() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)
        session.callbacks!!.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED))

        session.callbacks!!.onStatus("stale-job", PluginProtocol.JobState.DOWNLOADING, 10)
        session.callbacks!!.onRejected("stale-job", PluginProtocol.ErrorCode.BUSY)
        assertEquals(ImportState.Succeeded("Song.mp3"), controller.state.value)
        assertEquals(writer.writes.size, 1)
    }

    // input plausibility (full validation stays with the plugin)
    @Test fun implausibleInputNeverStartsAJob() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        listOf("", "   ", "not a url", "ftp://example.test/x", "http://", "https://")
            .forEach { bad ->
                assertFalse("expected refusal for '$bad'", controller.start(bad))
            }
        assertEquals(0, session.starts)
        assertEquals(ImportState.Failed(PluginProtocol.ErrorCode.INVALID_INPUT), controller.state.value)
    }

    @Test fun losingCapabilityAfterStartIsUnavailableAgain() {
        val controller = controller()
        controller.onCapabilities(PluginProtocol.CAP_IMPORT)
        controller.start(valid)
        val job = activeJob(controller)
        session.callbacks!!.onResult(job, resultBundle(job, PluginProtocol.JobState.SUCCEEDED))
        controller.onCapabilities(0)
        assertTrue(controller.state.value is ImportState.Unavailable)
        assertFalse(controller.canStart.value)
    }

    @Test fun closeReleasesTheSession() {
        val controller = controller()
        controller.close()
        assertTrue(session.closed)
    }

    @Test fun cancellingDuringHostCopyInterruptsTheWriterAndAcknowledgesWithoutSuccess() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val writer = object : ImportFileWriter {
            override fun canWrite(treeUri: Uri) = true
            override fun write(treeUri: Uri, sourceUri: Uri, baseName: String, mimeType: String): WriteResult {
                entered.countDown()
                try { java.util.concurrent.CountDownLatch(1).await() }
                finally { interrupted.countDown() }
                return WriteResult.Failure(WriteResult.Reason.UNKNOWN_ERROR)
            }
        }
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val controller = ImportController(session, writer, { target }, { refreshes.incrementAndGet() },
            parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        try {
            controller.onCapabilities(PluginProtocol.CAP_IMPORT)
            assertTrue(controller.start(valid))
            assertTrue(session.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val id = requireNotNull(session.lastJobId)
            session.callbacks!!.onResult(id, resultBundle(id, PluginProtocol.JobState.SUCCEEDED))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(session.acknowledged.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(ImportState.Cancelled, controller.state.value)
            assertEquals(0, refreshes.get())
        } finally { controller.close() }
    }

    @Test fun slowProviderRunsOffCallerThreadAndDuplicateResultCannotCopyTwice() {
        val caller = Thread.currentThread()
        val writeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val copies = java.util.concurrent.atomic.AtomicInteger()
        val checkedOffThread = java.util.concurrent.atomic.AtomicBoolean()
        val copiedOffThread = java.util.concurrent.atomic.AtomicBoolean()
        val slowWriter = object : ImportFileWriter {
            override fun canWrite(treeUri: Uri): Boolean {
                checkedOffThread.set(Thread.currentThread() !== caller)
                return true
            }
            override fun write(treeUri: Uri, sourceUri: Uri, baseName: String, mimeType: String): WriteResult {
                copiedOffThread.set(Thread.currentThread() !== caller)
                copies.incrementAndGet()
                writeEntered.countDown()
                check(releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return WriteResult.Success(Uri.parse("content://library/result.mp3"), "result.mp3")
            }
        }
        val controller = ImportController(
            session, slowWriter, { target },
            parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        ) // Real default IO dispatcher; only UI scheduling is replaced for this test.
        try {
            controller.onCapabilities(PluginProtocol.CAP_IMPORT)
            assertTrue(controller.start(valid))
            assertTrue(session.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val id = requireNotNull(session.lastJobId)
            val result = resultBundle(id, PluginProtocol.JobState.SUCCEEDED)
            session.callbacks!!.onResult(id, result)
            assertTrue(writeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            session.callbacks!!.onResult(id, result)
            assertFalse(controller.start(other))
            assertEquals(1, copies.get())
            assertTrue(controller.state.value is ImportState.Active)
            releaseWrite.countDown()
            assertTrue(session.acknowledged.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(checkedOffThread.get())
            assertTrue(copiedOffThread.get())
            assertEquals(ImportState.Succeeded("result.mp3"), controller.state.value)
        } finally {
            releaseWrite.countDown()
            controller.close()
        }
    }
}
