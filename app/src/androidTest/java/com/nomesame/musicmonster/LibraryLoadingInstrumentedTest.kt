package com.nomesame.musicmonster

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.fixtures.LibraryDocumentsProvider
import com.nomesame.musicmonster.ui.screens.PlayerScreen
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real SAF cursors and Compose UI; native media opening is deliberately blocked. */
class LibraryLoadingInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun diskDurationCacheSurvivesNewInstanceAndRejectsModifiedFileStamps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("owned-speed-cache-", ".json", context.cacheDir)
        val uri = android.net.Uri.parse("content://owned/cache-test")
        try {
            val cache = com.nomesame.musicmonster.data.MediaDurationCache(file)
            cache.put(uri, 64, 1234, 8000)
            cache.flush()
            val restored = com.nomesame.musicmonster.data.MediaDurationCache(file)
            assertEquals(8000L, restored.get(uri, 64, 1234))
            assertNull(restored.get(uri, 65, 1234))
            assertNull(restored.get(uri, 64, 1235))
        } finally { assertTrue(file.delete()) }
    }
    @Test fun twoThousandTitlesAreUsableBeforeAnyNativeMetadataReadCompletes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertTrue("Separate validation installation required", context.packageName.endsWith(".validation"))
        val id = "Speed-${UUID.randomUUID()}"
        val root = File(context.cacheDir, "library-fixtures/$id").apply { check(mkdirs()) }
        val prefsName = "owned-speed-$id"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val children = AtomicInteger()
        val documents = AtomicInteger()
        val store = ViewModelStore()
        var host: ComponentActivity? = null
        var model: MainViewModel? = null
        try {
            repeat(2000) { File(root, "Track-$it.mp3").writeText("owned fixture") }
            val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".library-fixture", id)
            assertTrue(prefs.edit().putString("library_tree_uri", tree.toString()).commit())
            LibraryDocumentsProvider.onQuery = { if (it) children.incrementAndGet() else documents.incrementAndGet() }
            LibraryDocumentsProvider.beforeOpen = {
                entered.countDown()
                assertTrue("Test metadata gate timed out", release.await(30, TimeUnit.SECONDS))
            }
            val app = object : Application() {
                init { attachBaseContext(context) }
                override fun getSharedPreferences(name: String?, mode: Int) = prefs
            }
            host = launchComposeHost()
            val started = SystemClock.elapsedRealtime()
            inst.runOnMainSync {
                val vm = MainViewModel(app)
                model = vm
                store.put("speed", vm)
                requireNotNull(host).setContent { MyApplicationTheme { PlayerScreen(vm, {}, {}, {}, {}, {}) } }
                vm.loadSongs() // MainActivity normally starts this after checking the folder grant.
            }
            compose.waitUntil(10_000) { model!!.songs.value.isNotEmpty() }
            val first = SystemClock.elapsedRealtime() - started
            compose.waitUntil(10_000) { model!!.songs.value.size == 2000 && !model!!.libraryScanning.value }
            val complete = SystemClock.elapsedRealtime() - started
            compose.onNodeWithText("Track-0", substring = false).assertIsDisplayed()
            assertTrue("Metadata path was never exercised", entered.await(10, TimeUnit.SECONDS))
            assertEquals("Metadata is still blocked", 1L, release.count)
            assertEquals(2000, model!!.songs.value.size)
            assertTrue(model!!.songs.value.all { it.durationMs == 0L })
            assertEquals("One child cursor, not one query per file", 1, children.get())
            assertEquals(1, documents.get())
            compose.onNodeWithTag("library_loading").assertDoesNotExist()
            // Sorting/filtering must remain interactive while native metadata is blocked.
            compose.onNodeWithTag("library_preset").performClick()
            assertEquals(1L, release.count)
            android.util.Log.i("LibrarySpeed", "files=2000 first_list_ms=$first complete_names_ms=$complete document_queries=${documents.get()} child_queries=${children.get()}")
        } finally {
            inst.runOnMainSync { store.clear(); host?.finish() }
            release.countDown()
            LibraryDocumentsProvider.beforeOpen = null
            LibraryDocumentsProvider.onQuery = null
            assertEquals(File(context.cacheDir, "library-fixtures").canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.deleteRecursively())
            assertTrue(context.deleteSharedPreferences(prefsName))
        }
    }
}
