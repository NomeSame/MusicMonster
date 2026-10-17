package com.nomesame.musicmonster

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.extension.api.ExtensionContract
import com.nomesame.musicmonster.extensions.ExtensionRegistry
import com.nomesame.musicmonster.fixtures.ExtensionProbeActivity
import com.nomesame.musicmonster.state.ExtensionController
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Requires an installed signed producer and its owned fixture text, supplied as runner arguments. */
class ExtensionRoundTripInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val tree get() = DocumentsContract.buildTreeDocumentUri(context.packageName + ".rename-fixture", "root")
    private lateinit var host: ExtensionProbeActivity
    private lateinit var scope: CoroutineScope
    private lateinit var controller: ExtensionController
    @Volatile private var done = false
    private var output: android.net.Uri? = null
    private var producerUid = -1
    private val folder get() = File(context.cacheDir, "rename-fixtures").apply { mkdirs() }
    private var before = emptySet<String>()
    @Before fun setup() {
        before = folder.list().orEmpty().toSet()
        host = launchTestActivity(ExtensionProbeActivity::class.java)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        assertTrue("An existing extension request must never be used as a fixture",
            context.getSharedPreferences("extension_output", 0).all.isEmpty())
        inst.runOnMainSync {
            controller = ExtensionController(context, scope) { done = true }
            host.onResult = controller::result
            scope.launch { controller.requests.collect {
                output = it.data
                assertEquals(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION, it.flags)
                assertEquals(output, it.clipData!!.getItemAt(0).uri)
                assertNotEquals(tree, output)
                assertNotEquals("root", DocumentsContract.getDocumentId(output!!))
                host.produce(it)
            } }
        }
    }
    @After fun cleanup() {
        inst.runOnMainSync { controller.cancel(); host.finish() }
        waitFor { !controller.busy.value }
        scope.cancel()
        folder.listFiles().orEmpty().filter { it.name !in before }.forEach { assertTrue(it.delete()) }
    }
    private fun waitFor(timeout: Long = 120_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Extension condition timed out", condition())
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun start() {
        val pkg = requireNotNull(InstrumentationRegistry.getArguments().getString("extensionPackage"))
        producerUid = context.packageManager.getApplicationInfo(pkg, 0).uid
        val action = ExtensionRegistry(context).actions().single { it.component.packageName == pkg }
        inst.runOnMainSync { controller.start(action, tree, "en") }
        waitFor(15_000) { nodes(inst.uiAutomation.rootInActiveWindow).any { it.className == "android.widget.EditText" } }
    }
    @Test fun signedProducerReturnsOneFileThroughWriteOnlyChildGrant() {
        start()
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(output!!, -1, producerUid, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(output!!, -1, producerUid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(tree, -1, producerUid, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        val text = requireNotNull(InstrumentationRegistry.getArguments().getString("extensionFixtureText"))
        val input = nodes(inst.uiAutomation.rootInActiveWindow).first { it.className == "android.widget.EditText" }
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        val primary = nodes(inst.uiAutomation.rootInActiveWindow).first { it.className == "android.widget.Button" && it.isEnabled }
        assertTrue(primary.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor { done || controller.failed.value }
        assertFalse("Producer failed", controller.failed.value)
        assertTrue(done)
        val results = folder.listFiles().orEmpty().filter { it.name !in before }
        assertEquals(1, results.size)
        assertTrue(results.single().length() > 0)
        assertFalse(results.single().name.endsWith(".partial"))
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(results.single().absolutePath)
            assertTrue((0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            })
        } finally { extractor.release() }
        assertFalse(controller.busy.value)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(output!!, -1, producerUid, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
    @Test fun dismissingProducerDiscardsItsPartialDocument() {
        start()
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor { !controller.busy.value }
        assertFalse(done)
        assertEquals(before, folder.list().orEmpty().toSet())
    }
}
