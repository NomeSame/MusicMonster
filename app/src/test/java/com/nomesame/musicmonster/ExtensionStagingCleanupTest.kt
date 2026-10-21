package com.nomesame.musicmonster

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.data.ExtensionDocumentRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ExtensionStagingCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var name: String? = ".extension-owned.partial"
    private var exists = true
    private var deletes = 0
    private val uri = Uri.parse("content://owned.cleanup/document/stable-id")
    private fun repository(): ExtensionDocumentRepository {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
                MatrixCursor(projection ?: arrayOf("_display_name")).apply { if (exists) addRow(arrayOf(name)) }
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                check(method.contains("delete", ignoreCase = true)); deletes++; exists = false
                return Bundle()
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "owned.cleanup" })
        ShadowContentResolver.registerProviderInternal("owned.cleanup", provider)
        return ExtensionDocumentRepository(context)
    }
    @Test fun committedDocumentWithStableUriSurvivesRecoveryAndCancellation() {
        val repo = repository()
        name = "finished.mp3" // Same document ID after the provider's rename.
        assertTrue(repo.discard(uri))
        assertTrue(exists)
        assertEquals(0, deletes)
    }
    @Test fun ownedUnfinishedStageIsDeletedAndMissingDocumentNeedsNoDelete() {
        val repo = repository()
        assertTrue(repo.discard(uri)); assertEquals(1, deletes)
        assertTrue(repo.discard(uri)); assertEquals(1, deletes)
    }
    @Test fun missingMetadataDoesNotAuthorizeDeletion() {
        val repo = repository(); name = null
        assertFalse(repo.discard(uri)); assertEquals(0, deletes)
    }
}
