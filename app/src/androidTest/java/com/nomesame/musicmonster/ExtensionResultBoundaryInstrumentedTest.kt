package com.nomesame.musicmonster

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.extension.api.ExtensionContract as Contract
import com.nomesame.musicmonster.extensions.ExtensionRegistry
import com.nomesame.musicmonster.state.ExtensionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ExtensionResultBoundaryInstrumentedTest {
    @Test fun restoredJournalRejectsMismatchedResultAndCancellationCannotLaunchQueuedRequest() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val journal = context.getSharedPreferences("extension_output", 0)
        assertTrue("Never replace an existing request with a fixture", journal.all.isEmpty())
        val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".rename-fixture", "root")
        val pkg = requireNotNull(InstrumentationRegistry.getArguments().getString("extensionPackage"))
        val action = ExtensionRegistry(context).actions().single { it.component.packageName == pkg }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var completions = 0
        val original = ExtensionController(context, scope) { completions++ }
        inst.runOnMainSync { original.start(action, tree, "en") }
        val request = runBlocking { withTimeout(10_000) { original.requests.first() } }
        val output = requireNotNull(request.data)
        val file = File(context.cacheDir, "rename-fixtures/${DocumentsContract.getDocumentId(output)}")
        try {
            context.contentResolver.openOutputStream(output)!!.use { it.write(byteArrayOf(1, 2, 3)) }
            val restored = ExtensionController(context, scope) { completions++ }
            assertTrue(restored.busy.value)
            val wrong = Intent().putExtra(Contract.EXTRA_VERSION, Contract.VERSION)
                .putExtra(Contract.EXTRA_REQUEST, UUID.randomUUID().toString()).putExtra(Contract.EXTRA_NAME, "fixture.wav")
            inst.runOnMainSync { restored.result(Activity.RESULT_OK, wrong) }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (restored.busy.value && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertFalse(restored.busy.value)
            assertTrue(restored.failed.value)
            assertEquals(0, completions)
            assertFalse(file.exists())
            assertTrue(journal.all.isEmpty())
            inst.runOnMainSync { original.cancel(); assertFalse(original.canLaunch(request)) }
        } finally {
            scope.cancel()
            file.delete()
            journal.edit().clear().commit()
        }
    }
}
