package com.nomesame.musicmonster.state

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nomesame.musicmonster.data.ExtensionJournal
import com.nomesame.musicmonster.data.PendingExtension
import com.nomesame.musicmonster.data.ExtensionDocumentRepository
import com.nomesame.musicmonster.extension.api.ExtensionContract as Contract
import com.nomesame.musicmonster.extensions.ExtensionAction
import com.nomesame.musicmonster.extensions.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.nomesame.musicmonster.extension.api.DocumentSequence as Sequence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lifecycle-owned orchestration for optional document producers, with a persisted destination. */
class ExtensionController(
    private val context: Context,
    private val scope: CoroutineScope,
    extensionsEnabled: Boolean = true,
    private val onCompleted: () -> Unit,
) {
    private val registry = ExtensionRegistry(context, extensionsEnabled)
    private val documents = ExtensionDocumentRepository(context)
    private val journal = ExtensionJournal(context)
    private val _actions = MutableStateFlow(emptyList<ExtensionAction>())
    val actions = _actions.asStateFlow()
    private val _busy = MutableStateFlow((journal.read() != null))
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private val launches = Channel<Intent>(Channel.BUFFERED)
    val requests = launches.receiveAsFlow()
    private val folderSelections = Channel<Unit>(Channel.CONFLATED)
    val folderRequests = folderSelections.receiveAsFlow()
    private var awaitingFolder: Triple<ExtensionAction, String, Int?>? = null
    private var finishing = false
    private var cancelRequested = false
    private val outputLock = Mutex()

    private fun documentChannel(requestId: String): ResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (resultCode != Sequence.COMMIT_NEXT || resultData == null) return
            val data = Bundle(resultData)
            @Suppress("DEPRECATION")
            val reply = data.getParcelable<ResultReceiver>(Sequence.REPLY) ?: return
            val nonce = data.getString(Sequence.NONCE) ?: return
            scope.launch {
                outputLock.withLock {
                    var successor: Uri? = null
                    var committed = false
                    try {
                        check(!cancelRequested && !finishing)
                        val pending = requireNotNull(journal.read())
                        check(pending.id == requestId && data.getString(Sequence.REQUEST) == requestId)
                        check(pending.output.toString() == data.getString(Sequence.OUTPUT))
                        check(registry.contains(pending.component))
                        val name = requireNotNull(data.getString(Sequence.NAME))
                        // Allocate before publication: inability to prepare a successor must
                        // not produce an acknowledged success without a usable next slot.
                        successor = withContext(Dispatchers.IO) { documents.create(pending.tree) }
                        context.revokeUriPermission(pending.output, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        withContext(Dispatchers.IO) { documents.complete(pending.tree, pending.output, name) }
                        committed = true
                        withContext(Dispatchers.IO + NonCancellable) {
                            check(journal.save(pending.copy(output = successor!!)))
                        }
                        onCompleted()
                        check(!cancelRequested && !finishing && registry.contains(pending.component))
                        context.grantUriPermission(pending.component.packageName, successor, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        reply.send(Sequence.OK, Bundle().apply {
                            putString(Sequence.NONCE, nonce)
                            putString(Sequence.OUTPUT, successor.toString())
                        })
                    } catch (_: Exception) {
                        if (successor != null) withContext(Dispatchers.IO + NonCancellable) {
                            if (documents.discard(successor!!) && committed) journal.clear()
                        }
                        if (!cancelRequested) _failed.value = true
                        reply.send(Sequence.ERROR, Bundle().apply { putString(Sequence.NONCE, nonce) })
                    }
                }
            }
        }
    }

    fun refresh() { _actions.value = registry.actions() }
    fun dismissError() { _failed.value = false }

    fun start(action: ExtensionAction, tree: Uri?, language: String, accentColor: Int? = null) {
        if (_busy.value) return
        if (!registry.contains(action.component)) { _failed.value = true; refresh(); return }
        _failed.value = false
        if (tree == null) {
            awaitingFolder = Triple(action, language, accentColor)
            _busy.value = true
            folderSelections.trySend(Unit)
            return
        }
        cancelRequested = false
        _busy.value = true
        val request = UUID.randomUUID().toString()
        scope.launch {
            var created: Uri? = null
            try {
                val uri = withContext(Dispatchers.IO) { documents.create(tree).also { created = it } }
                check(withContext(Dispatchers.IO) { journal.save(PendingExtension(uri, tree, action.component, request)) })
                check(!cancelRequested && registry.contains(action.component))
                launches.send(Intent(Contract.ACTION).setComponent(action.component).setData(uri).apply {
                    clipData = ClipData.newRawUri("output", uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    putExtra(Contract.EXTRA_VERSION, Contract.VERSION)
                    putExtra(Contract.EXTRA_REQUEST, request)
                    putExtra(Contract.EXTRA_LANGUAGE, language)
                    putExtra(Sequence.EXTRA_CHANNEL, documentChannel(request))
                    accentColor?.let { putExtra(Contract.EXTRA_ACCENT_COLOR, it) }
                })
            } catch (_: Exception) {
                withContext(Dispatchers.IO + NonCancellable) {
                    if (created == null || documents.discard(created!!)) journal.clear()
                    else journal.save(PendingExtension(created!!, tree, action.component, request))
                }
                _busy.value = (journal.read() != null)
                _failed.value = !cancelRequested
            }
        }
    }

    fun result(code: Int, data: Intent?) {
        if (finishing) return
        finishing = true
        scope.launch {
          outputLock.withLock {
            val pending = journal.read()
            if (pending == null) { _busy.value = false; finishing = false; return@withLock }
            val uri = pending.output
            val component = pending.component
            var completed = false
            try {
                // End the delegated write capability before exposing a finished library item.
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (code == Activity.RESULT_OK) {
                    check(registry.contains(component))
                    check(data?.getIntExtra(Contract.EXTRA_VERSION, -1) == Contract.VERSION)
                    check(data?.getStringExtra(Contract.EXTRA_REQUEST) == pending.id)
                    val name = requireNotNull(data?.getStringExtra(Contract.EXTRA_NAME))
                    withContext(Dispatchers.IO) { documents.complete(pending.tree, uri, name) }
                    completed = true
                }
            } catch (_: Exception) { _failed.value = true }
            finally {
                withContext(Dispatchers.IO + NonCancellable) {
                    if (completed || documents.discard(uri)) journal.clear()
                    // A provider refusing cleanup retains the journal for an explicit retry.
                }
                _busy.value = (journal.read() != null)
                finishing = false
            }
            if (completed) onCompleted()
          }
        }
    }

    /** Continue only the explicit action that asked for a destination; cancellation starts nothing. */
    fun folderSelected(tree: Uri?) {
        val pending = awaitingFolder ?: return
        awaitingFolder = null
        _busy.value = false
        if (tree != null) start(pending.first, tree, pending.second, pending.third)
    }

    fun needsFolder(): Boolean = awaitingFolder != null

    fun cancel() {
        cancelRequested = true
        if (awaitingFolder != null) { awaitingFolder = null; _busy.value = false }
        result(Activity.RESULT_CANCELED, null)
    }

    fun launchFailed() { _failed.value = true; result(Activity.RESULT_CANCELED, null) }

    fun canLaunch(request: Intent): Boolean = !cancelRequested && !finishing && _busy.value &&
        journal.read()?.id == request.getStringExtra(Contract.EXTRA_REQUEST) &&
        request.component?.let(registry::contains) == true
}
