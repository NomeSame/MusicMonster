package com.nomesame.musicmonster

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.ImportFileNames
import com.nomesame.musicmonster.data.LibraryImportWriter
import com.nomesame.musicmonster.data.SongRepository
import com.nomesame.musicmonster.plugin.ImportJobClient
import com.nomesame.musicmonster.plugin.PluginProbe
import com.nomesame.musicmonster.plugin.PluginRegistry
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import com.nomesame.musicmonster.state.ImportController
import com.nomesame.musicmonster.state.ImportState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real end-to-end import: the **host** binds the **separately installed, real plugin
 * service** (cross-process, `Messenger`), negotiates `CAP_IMPORT`, runs a job against a real
 * source, receives live progress and the terminal result, writes the delivered MP3 through the
 * real SAF data layer into a writable tree, and verifies the written bytes are a real MP3.
 *
 * Nothing is faked for the plugin/binder seam: [ImportJobClient] is the production session and
 * [LibraryImportWriter] the production writer. The only test-owned pieces are the destination
 * tree (a real, exported provider in the test APK — see [E2eTreeDocumentsProvider]) and the
 * source URL argument.
 *
 * The precondition is a **visible failure**, never an `Assume`: without a matching, import-
 * capable plugin the test goes red with a message that says exactly what is missing.
 */
@RunWith(AndroidJUnit4::class)
class ImportEndToEndInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val host: Context = instrumentation.targetContext
    private val test: Context = instrumentation.context
    private val prefs = host.getSharedPreferences("import_e2e_fixture", Context.MODE_PRIVATE)

    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        E2eTreeDocumentsProvider.AUTHORITY,
        E2eTreeDocumentsProvider.ROOT_ID,
    )

    private var controller: ImportController? = null
    private var previousTreePref: String? = null
    private var treePrefTouched = false

    @Test
    fun hostImportsRealPluginMp3IntoTreeAndNeverOverwrites() {
        // ---- source: argument overrides the constant; a missing argument is NOT a skip ----
        val url = InstrumentationRegistry.getArguments().getString("importAudioUrl")
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_IMPORT_AUDIO_URL

        installWritableTree()

        // ---- precondition: a real, import-capable plugin service must be installed ----
        val component: ComponentName = PluginRegistry(host).available().firstOrNull()
            ?: throw AssertionError(
                "plugin not installed: no enabled, same-signer service advertises " +
                    "'${PluginProtocol.BIND_ACTION}' (see private-plugin). " +
                    "Install a compatible plugin debug APK before running this test.",
            )
        val caps = negotiateCapabilities(component)
        assertEquals(
            "plugin handshake failed for $component: probeCapabilities result=${caps.result}",
            PluginProbe.Result.READY,
            caps.result,
        )
        assertTrue(
            "plugin not capable: CAPS_REPLY did not announce CAP_IMPORT " +
                "(capabilities=${caps.capabilities})",
            PluginProtocol.hasCapability(caps.capabilities, PluginProtocol.CAP_IMPORT),
        )

        // ---- run 1: the real job through the real seams ----
        val libraryChanged = AtomicInteger(0)
        val activeStates = mutableListOf<ImportState.Active>()
        onMain {
            val created = ImportController(
                session = ImportJobClient(host),
                writer = LibraryImportWriter(host),
                currentTarget = { treeUri },
                onLibraryChanged = { libraryChanged.incrementAndGet() },
            )
            controller = created
            created.onCapabilities(caps.capabilities)
            assertTrue("controller refused the first start (state=${created.state.value})", created.start(url))
        }

        val firstTerminal = awaitTerminal(requireNotNull(controller), url, activeStates)

        // ---- assertions after success ----
        assertTrue(
            "import did not succeed: terminal=$firstTerminal, active statuses=$activeStates, url=$url",
            firstTerminal is ImportState.Succeeded,
        )
        val first = firstTerminal as ImportState.Succeeded
        assertTrue(
            "delivered displayName must end in .mp3, was '${first.displayName}'",
            first.displayName.endsWith(".mp3", ignoreCase = true),
        )
        assertTrue("no live progress (JOB_STATUS) was observed", activeStates.isNotEmpty())
        assertTrue(
            "progress must never decrease, was ${activeStates.map { it.progress }}",
            activeStates.map { it.progress }.zipWithNext().all { (a, b) -> b >= a },
        )
        assertTrue(
            "progress values must stay within 0..100, was ${activeStates.map { it.progress }}",
            activeStates.all { it.progress in 0..100 },
        )

        val root = tree()
        assertEquals(
            "exactly one file must be in the tree after one import, was ${fileNames(root)}",
            1,
            root.listFiles().size,
        )
        assertTrue(
            "tree must contain '${first.displayName}', was ${fileNames(root)}",
            first.displayName in fileNames(root),
        )
        assertRealMp3(first.displayName, readFromTree(first.displayName))
        assertEquals(
            "onLibraryChanged must fire exactly once for one successful import",
            1,
            libraryChanged.get(),
        )

        // ---- run 2: prove the no-overwrite rule at the real writer seam ----
        // The same source and therefore the same metadata-derived name; the provider already
        // holds it, so the writer must pick ' (2)' instead of overwriting. A second successful
        // job also shows the session is not stuck after the first result (ACK path works,
        // DECISIONS.md §8): the plugin frees the result and accepts the next job. Two real
        // downloads are deliberate — the anti-overwrite rule and the release-after-ACK path are
        // host/plugin behaviour that only a second real job exercises end to end.
        val activeStates2 = mutableListOf<ImportState.Active>()
        SystemClock.sleep(ACK_SETTLE_MS)
        onMain {
            assertTrue(
                "second start refused (state=${requireNotNull(controller).state.value})",
                requireNotNull(controller).start(url),
            )
        }
        val secondTerminal = awaitTerminal(requireNotNull(controller), url, activeStates2)

        assertTrue("second import did not succeed: $secondTerminal", secondTerminal is ImportState.Succeeded)
        val second = secondTerminal as ImportState.Succeeded
        val (stem, extension) = ImportFileNames.splitExtension(first.displayName)
        assertEquals(
            "a name collision must yield the ' (2)' suffix instead of overwriting " +
                "'${first.displayName}'",
            "$stem (2)$extension",
            second.displayName,
        )
        assertEquals(
            "both files must coexist, was ${fileNames(root)}",
            setOf(first.displayName, second.displayName),
            fileNames(root),
        )
        assertRealMp3(first.displayName, readFromTree(first.displayName))
        assertRealMp3(second.displayName, readFromTree(second.displayName))
        assertEquals(
            "onLibraryChanged must fire once per successful import",
            2,
            libraryChanged.get(),
        )

        // ---- the written files are visible through the existing library loader ----
        previousTreePref = prefs.getString("library_tree_uri", null)
        treePrefTouched = true
        prefs.edit().putString("library_tree_uri", treeUri.toString()).apply()
        val songs = SongRepository(host, prefs).load()
        val titles = songs.map { it.title }
        assertTrue(
            "SongRepository must surface the imported file(s) from the test tree; found $titles",
            titles.contains(stem) && titles.contains("$stem (2)"),
        )
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Makes the test tree usable by the host exactly like a user-picked SAF folder. */
    private fun installWritableTree() {
        // Read+write+prefix grant, mirroring what the system SAF picker attaches to a picked
        // tree: it gives the host access and makes this test package visible under API 30+
        // package-visibility filtering. The prefix flag is not cosmetic — see
        // [E2eTreeDocumentsProvider.TREE_GRANT_FLAGS]: without it the platform records no
        // UriPermission at all and DocumentFile.canWrite() would fail. The provider starts on
        // the first query below.
        val granted = java.util.concurrent.CountDownLatch(1)
        val receiver = android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()) {
            granted.countDown(); true
        })
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
                android.os.Messenger(binder).send(android.os.Message.obtain(null, 1).apply { replyTo = receiver })
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(host.bindService(android.content.Intent().setComponent(ComponentName(
            test.packageName, E2eTreeGrantService::class.java.name)), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue("provider-owner grant", granted.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        finally { host.unbindService(connection) }
        resetTree()
        val root = tree()
        assertTrue("fixture WRITE denied: uri=${root.uri}, type=${root.type}, " +
            "permission=${host.checkCallingOrSelfUriPermission(root.uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}",
            root.canWrite())
    }

    /** Empties the tree through the real provider protocol (idempotent, keeps the root). */
    private fun resetTree() {
        for (child in tree().listFiles()) {
            DocumentsContract.deleteDocument(host.contentResolver, child.uri)
        }
    }

    private fun negotiateCapabilities(component: ComponentName): PluginProbe.CapabilityResult {
        val result = AtomicReference<PluginProbe.CapabilityResult>()
        val probe = AtomicReference<PluginProbe>()
        onMain {
            val created = PluginProbe(host)
            probe.set(created)
            created.probeCapabilities(component) { result.set(it) }
        }
        val deadline = SystemClock.uptimeMillis() + CAPABILITIES_TIMEOUT_MS
        while (result.get() == null) {
            if (SystemClock.uptimeMillis() > deadline) {
                onMain { probe.get()?.close() }
                throw AssertionError(
                    "capability probe for $component did not answer within " +
                        "${CAPABILITIES_TIMEOUT_MS / 1000}s",
                )
            }
            SystemClock.sleep(POLL_MS)
        }
        onMain { probe.get()?.close() }
        return result.get()!!
    }

    /** Polls the controller state on the main thread until a terminal state or the timeout. */
    private fun awaitTerminal(
        controller: ImportController,
        url: String,
        activeStates: MutableList<ImportState.Active>,
    ): ImportState {
        val deadline = SystemClock.uptimeMillis() + JOB_TIMEOUT_MS
        while (true) {
            val state = onMain { controller.state.value }
            if (state is ImportState.Active && activeStates.none { it.progress == state.progress && it.state == state.state }) {
                activeStates += state
            }
            if (state is ImportState.Succeeded || state is ImportState.Failed || state is ImportState.Cancelled) {
                return state
            }
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError(
                    "import did not reach a terminal state within ${JOB_TIMEOUT_MS / 1000}s; " +
                        "last=${state}, active statuses=$activeStates, url=$url",
                )
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun tree(): DocumentFile = requireNotNull(DocumentFile.fromTreeUri(host, treeUri)) {
        "test tree URI is not a readable tree: $treeUri"
    }

    private fun fileNames(root: DocumentFile): Set<String> = root.listFiles().mapNotNull { it.name }.toSet()

    private fun readFromTree(name: String): ByteArray {
        val doc = tree().findFile(name)
            ?: throw AssertionError("document '$name' is not in the test tree")
        val stream = host.contentResolver.openInputStream(doc.uri)
            ?: throw AssertionError("document '$name' has no readable stream")
        return stream.use { it.readBytes() }
    }

    /** Real MP3 check: non-empty and either an ID3 tag or an MPEG audio frame sync. */
    private fun assertRealMp3(name: String, bytes: ByteArray) {
        assertTrue("'$name' must not be empty", bytes.isNotEmpty())
        val hasId3 = bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        var hasFrame = false
        val limit = minOf(bytes.size - 1, 4096)
        var i = 0
        while (i < limit) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            // 11 set bits = MPEG audio frame sync (0xFF followed by 0xEx/0xFx).
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                hasFrame = true
                break
            }
            i++
        }
        assertTrue(
            "'$name' is not a real MP3: no ID3 header and no MPEG frame sync " +
                "(size=${bytes.size}, head=${bytes.take(4).joinToString(" ") { "0x%02X".format(it) }})",
            hasId3 || hasFrame,
        )
    }

    private fun <T> onMain(block: () -> T): T {
        val box = AtomicReference<T>()
        instrumentation.runOnMainSync { box.set(block()) }
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }

    @After
    fun tearDown() {
        onMain { controller?.close() }
        controller = null
        if (treePrefTouched) {
            if (previousTreePref == null) prefs.edit().remove("library_tree_uri").apply()
            else prefs.edit().putString("library_tree_uri", previousTreePref).apply()
            treePrefTouched = false
        }
        runCatching { resetTree() }
        runCatching {
            host.contentResolver.call(treeUri, "revoke-fixture-tree", null, null)
        }
    }

    private companion object {
        /** Stable, licence-free source (CC public domain LibriVox item on Archive.org). */
        const val DEFAULT_IMPORT_AUDIO_URL =
            "https://archive.org/details/gettysburg_shurtagal_librivox"

        const val JOB_TIMEOUT_MS = 240_000L
        const val CAPABILITIES_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L

        /** Gives the plugin's main-thread message queue a moment to process the result ACK. */
        const val ACK_SETTLE_MS = 1_000L
    }
}
