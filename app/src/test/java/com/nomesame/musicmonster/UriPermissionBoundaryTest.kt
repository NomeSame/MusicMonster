package com.nomesame.musicmonster

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

/**
 * Contract for MainActivity's persistable SAF grants.
 *
 * The `OpenDocumentTree`/`OpenDocument` callbacks are thin wiring: they hand the
 * chosen [Uri] to a single private helper, so the persisted flag mask is pinned
 * here by invoking exactly that helper — the same seam the callback uses.
 * Driving the real ActivityResult launcher instead would need onCreate/Compose/
 * ViewModel plus a fake `startActivityForResult` round-trip, which adds no
 * coverage over the helper call it would ultimately reach. Therefore this test
 * pins the helper contract and documents the limitation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], shadows = [UriGrantContractShadow::class])
class UriPermissionBoundaryTest {

    @Before fun setup() { UriGrantContractShadow.reset() }

    private fun invoke(method: String, uri: Uri) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val target = MainActivity::class.java.declaredMethods.single { it.name == method }
        target.isAccessible = true
        target.invoke(activity, uri)
    }

    private fun folderUri() = Uri.parse("content://probe/tree/chosen")

    @Test fun folderCallbackPersistsReadAndWriteWhenProviderOffersWrite() {
        UriGrantContractShadow.writable = true
        invoke("takeFolderPermission", folderUri())
        assertEquals(listOf(READ or WRITE), UriGrantContractShadow.persisted.map { it.second })
    }

    @Test fun folderCallbackFallsBackToReadOnlyWithoutWriteOffer() {
        invoke("takeFolderPermission", folderUri())
        assertEquals(listOf(READ), UriGrantContractShadow.persisted.map { it.second })
    }

    @Test fun backgroundPickerStaysReadOnly() {
        invoke("takeReadPermission", Uri.parse("content://probe/image/picked"))
        assertEquals(listOf(READ), UriGrantContractShadow.persisted.map { it.second })
    }

    @Test fun releaseReleasesBothModesSoOldGrantsDoNotLinger() {
        invoke("releaseReadPermission", folderUri())
        assertEquals(listOf(READ or WRITE), UriGrantContractShadow.released.map { it.second })
    }

    private companion object {
        val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

@Implements(ContentResolver::class)
class UriGrantContractShadow : ShadowContentResolver() {

    @Implementation override fun takePersistableUriPermission(uri: Uri, mode: Int) {
        // Match UriGrantsManagerService's checkFlagsArgument: only the
        // read/write mode bits are legal here; PERSISTABLE describes the
        // offered Intent grant, not this mask.
        require(mode and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv() == 0)
        if (mode and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && !writable) {
            // A provider that only offered read rejects the combined persist.
            throw SecurityException("provider did not offer write access")
        }
        persisted.add(uri to mode)
    }

    @Implementation override fun releasePersistableUriPermission(uri: Uri, mode: Int) {
        released.add(uri to mode)
    }

    companion object {
        /** Whether the fake provider offered write access in the current test. */
        var writable = false
        val persisted = mutableListOf<Pair<Uri, Int>>()
        val released = mutableListOf<Pair<Uri, Int>>()
        fun reset() {
            writable = false
            persisted.clear()
            released.clear()
        }
    }
}
