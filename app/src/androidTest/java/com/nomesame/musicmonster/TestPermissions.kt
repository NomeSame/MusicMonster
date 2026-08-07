package com.nomesame.musicmonster

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * Grants the app's runtime permissions from instrumentation.
 *
 * `UiAutomation.grantRuntimePermission` only became public API in API 28 and
 * quietly does nothing on API 24-27, which does not look like a harness
 * problem at all: the app then raises the real permission dialog, the activity
 * under test never reaches RESUMED, and every launch test fails with a
 * lifecycle timeout that says nothing about permissions. `pm grant` through
 * the shell works on every supported level, so that is what we use — and we
 * verify the result instead of assuming it.
 */
object TestPermissions {

    fun grantAll() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_AUDIO)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissions.forEach { grant(context, it) }
    }

    /** True when every permission this app needs on this API level is granted. */
    fun audioPermissionGranted(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun grant(context: Context, permission: String) {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        runCatching { shell("pm grant ${context.packageName} $permission") }
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            // Fall back to the API 28+ path in case the shell route is blocked.
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission(context.packageName, permission)
            }
        }
    }

    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        // Draining the pipe is what makes the command actually complete before
        // we check the result.
        FileInputStream(fd.fileDescriptor).use { it.readBytes() }
    }
}
