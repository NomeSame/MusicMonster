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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], shadows = [UriGrantContractShadow::class])
class UriPermissionBoundaryTest {
    @Before fun setup() { UriGrantContractShadow.modes.clear() }
    private fun invokeReadGrant(tree: Boolean) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val method = MainActivity::class.java.declaredMethods.single { it.name == "takeReadPermission" }
        method.isAccessible = true
        val uri = Uri.parse("content://probe/${if (tree) "tree" else "document"}/chosen")
        // The old helper had a boolean selecting the broken tree flags. The
        // corrected helper shares a single read-only grant for both pickers.
        if (method.parameterCount == 2) method.invoke(activity, uri, tree)
        else method.invoke(activity, uri)
    }
    @Test fun folderGrantUsesOnlyTheAcceptedReadMode() {
        invokeReadGrant(true)
        assertEquals(listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION), UriGrantContractShadow.modes)
    }
    @Test fun backgroundGrantUsesOnlyTheAcceptedReadMode() {
        invokeReadGrant(false)
        assertEquals(listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION), UriGrantContractShadow.modes)
    }
}

@Implements(ContentResolver::class)
class UriGrantContractShadow : ShadowContentResolver() {
    @Implementation override fun takePersistableUriPermission(uri: Uri, mode: Int) {
        modes.add(mode)
        // Match UriGrantsManagerService's checkFlagsArgument: persistability
        // belongs to the offered Intent grant, not this read/write mode mask.
        require(mode and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv() == 0)
    }
    companion object { val modes = mutableListOf<Int>() }
}
