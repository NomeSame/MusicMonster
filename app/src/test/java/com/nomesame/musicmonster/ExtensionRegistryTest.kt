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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExtensionRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shadow get() = shadowOf(context.packageManager)
    @Suppress("DEPRECATION")
    private fun install(signer: String = "aabb"): ActivityInfo {
        shadow.installPackage(PackageInfo().apply {
            packageName = context.packageName; signatures = arrayOf(Signature("aabb"))
            applicationInfo = context.applicationInfo
        })
        val app = ApplicationInfo().apply { packageName = "fixture.extension"; enabled = true }
        shadow.installPackage(PackageInfo().apply {
            packageName = app.packageName; applicationInfo = app; signatures = arrayOf(Signature(signer))
        })
        val activity = ActivityInfo().apply {
            packageName = app.packageName; name = "fixture.Producer"; applicationInfo = app
            enabled = true; exported = true; permission = Contract.PERMISSION; nonLocalizedLabel = "Create audio"
            metaData = Bundle().apply { putInt(Contract.META_VERSION, Contract.VERSION); putString(Contract.META_OUTPUT, Contract.AUDIO) }
        }
        shadow.addResolveInfoForIntent(Intent(Contract.ACTION), ResolveInfo().apply { activityInfo = activity })
        return activity
    }
    @Test fun absentExtensionContributesNothing() { assertTrue(ExtensionRegistry(context).actions().isEmpty()) }
    @Test fun acceptsOnlyMatchingSignedActivity() { install(); assertEquals("Create audio", ExtensionRegistry(context).actions().single().label) }
    @Test fun foreignSignerNeverContributesAction() { install("bbcc"); assertTrue(ExtensionRegistry(context).actions().isEmpty()) }
    @Test fun hiddenDisabledWrongVersionAndWrongPermissionStayUnavailable() {
        val activity = install(); val registry = ExtensionRegistry(context)
        activity.exported = false; assertTrue(registry.actions().isEmpty()); activity.exported = true
        activity.enabled = false; assertTrue(registry.actions().isEmpty()); activity.enabled = true
        activity.applicationInfo.enabled = false; assertTrue(registry.actions().isEmpty()); activity.applicationInfo.enabled = true
        activity.metaData.putInt(Contract.META_VERSION, 2); assertTrue(registry.actions().isEmpty())
        activity.metaData.putInt(Contract.META_VERSION, 1); activity.permission = null; assertTrue(registry.actions().isEmpty())
    }
    @Test fun unsupportedOutputAndMalformedLabelsStayUnavailable() {
        val activity = install(); val registry = ExtensionRegistry(context)
        activity.metaData.putString(Contract.META_OUTPUT, "image"); assertTrue(registry.actions().isEmpty())
        activity.metaData.putString(Contract.META_OUTPUT, Contract.AUDIO)
        activity.nonLocalizedLabel = "bad\nlabel"; assertTrue(registry.actions().isEmpty())
    }
}
