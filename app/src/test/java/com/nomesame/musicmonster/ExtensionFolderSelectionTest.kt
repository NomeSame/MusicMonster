package com.nomesame.musicmonster

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.nomesame.musicmonster.extension.api.ExtensionContract as Contract
import com.nomesame.musicmonster.extensions.ExtensionRegistry
import com.nomesame.musicmonster.state.ExtensionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], manifest = Config.NONE)
class ExtensionFolderSelectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = ExtensionController(context, scope, extensionsEnabled = true) { fail("No work before folder selection") }
    @After fun cleanup() { controller.cancel(); scope.cancel() }

    @Suppress("DEPRECATION")
    private fun install(): ActivityInfo {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = context.packageName; signatures = arrayOf(Signature("aabb"))
            applicationInfo = context.applicationInfo
        })
        val app = ApplicationInfo().apply { packageName = context.packageName; enabled = true }
        pm.installPackage(PackageInfo().apply {
            packageName = app.packageName; applicationInfo = app; signatures = arrayOf(Signature("aabb"))
        })
        val info = ActivityInfo().apply {
            packageName = app.packageName; name = "fixture.Producer"; applicationInfo = app
            enabled = true; exported = true; permission = Contract.PERMISSION; nonLocalizedLabel = "Create audio"
            metaData = Bundle().apply { putInt(Contract.META_VERSION, Contract.VERSION); putString(Contract.META_OUTPUT, Contract.AUDIO) }
        }
        pm.addResolveInfoForIntent(Intent(Contract.ACTION), ResolveInfo().apply { activityInfo = info })
        return info
    }
    private fun action() = ExtensionRegistry(context, enabled = true).actions().single()
    @Test fun missingFolderRequestsSelectionInsteadOfShowingAnError() = runBlocking {
        install()
        controller.start(action(), null, "de")
        withTimeout(1000) { controller.folderRequests.first() }
        assertTrue(controller.needsFolder())
        assertTrue(controller.busy.value)
        assertFalse(controller.failed.value)
        assertTrue(context.getSharedPreferences("extension_output", 0).all.isEmpty())
    }
    @Test fun cancelledPickerCanBeRetriedWithoutStartingWork() {
        install(); val action = action()
        controller.start(action, null, "en")
        controller.folderSelected(null)
        assertFalse(controller.busy.value)
        assertFalse(controller.needsFolder())
        assertFalse(controller.failed.value)
        controller.start(action, null, "en")
        assertTrue(controller.needsFolder())
    }
    @Test fun cancellationInvalidatesAlreadyQueuedFolderRequest() {
        install(); controller.start(action(), null, "en")
        controller.cancel()
        assertFalse(controller.needsFolder())
        controller.folderSelected(android.net.Uri.parse("content://fixture/tree/root"))
        assertFalse(controller.busy.value)
        assertFalse(controller.failed.value)
    }
    @Test fun removedExtensionCannotResumeAfterPicker() {
        val info = install(); controller.start(action(), null, "en")
        info.enabled = false
        controller.folderSelected(android.net.Uri.parse("content://fixture/tree/root"))
        assertFalse(controller.busy.value)
        assertTrue(controller.failed.value)
        assertTrue(controller.actions.value.isEmpty())
    }
    @Test fun disabledBuildCannotStartEvenAnInstalledProducer() {
        install()
        val disabled = ExtensionController(context, scope, extensionsEnabled = false) { fail("Disabled host started work") }
        disabled.refresh()
        disabled.start(action(), null, "en")
        assertTrue(disabled.actions.value.isEmpty())
        assertFalse(disabled.needsFolder())
        assertFalse(disabled.busy.value)
        assertTrue(disabled.failed.value)
    }
}
