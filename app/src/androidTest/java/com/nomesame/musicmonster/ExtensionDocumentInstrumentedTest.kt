package com.nomesame.musicmonster

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.ExtensionDocumentRepository
import com.nomesame.musicmonster.data.SongRepository
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ExtensionDocumentInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree get() = DocumentsContract.buildTreeDocumentUri(context.packageName + ".rename-fixture", "root")
    private val repo = ExtensionDocumentRepository(context)
    private val created = mutableListOf<android.net.Uri>()
    private val prefs get() = context.getSharedPreferences("extension-runtime-fixture", Context.MODE_PRIVATE)
    @After fun cleanup() { created.forEach { repo.discard(it) }; prefs.edit().clear().commit() }
    @Test fun partialOutputIsHiddenAndCompleteOutputUsesCollisionName() {
        val stage = repo.create(tree); created += stage
        context.contentResolver.openOutputStream(stage)!!.use { it.write(byteArrayOf(1, 2, 3)) }
        prefs.edit().putString("library_tree_uri", tree.toString()).commit()
        assertFalse(SongRepository(context, prefs).load().any { it.uri == stage })
        val name = "fixture-${UUID.randomUUID()}.wav"
        val first = repo.complete(tree, stage, name); created += first
        val secondStage = repo.create(tree); created += secondStage
        context.contentResolver.openOutputStream(secondStage)!!.use { it.write(byteArrayOf(4, 5)) }
        val second = repo.complete(tree, secondStage, name); created += second
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(first)!!.use { it.readBytes() })
        assertArrayEquals(byteArrayOf(4, 5), context.contentResolver.openInputStream(second)!!.use { it.readBytes() })
        assertTrue(SongRepository(context, prefs).load().any { it.uri == second })
    }
    @Test fun emptyOrInvalidResultsAreNeverPublishedAndCancellationDeletesStage() {
        val stage = repo.create(tree); created += stage
        assertTrue(runCatching { repo.complete(tree, stage, "empty.mp3") }.isFailure)
        assertTrue(runCatching { repo.complete(tree, stage, "../bad.mp3") }.isFailure)
        assertTrue(repo.discard(stage))
        assertFalse(File(context.cacheDir, "rename-fixtures/${DocumentsContract.getDocumentId(stage)}").exists())
    }
}
