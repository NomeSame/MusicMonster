package com.nomesame.musicmonster

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.FileInputStream

/**
 * Launch asynchronously and verify the real lifecycle with a bounded wait.
 * On the attached MIUI/API-33 device startActivitySync waits forever, with an
 * idle main thread, before ActivityScenarioRule can reach any test assertion.
 * This avoids that framework waiter; missing/blocked activities fail visibly.
 */
fun launchComposeHost(): ComponentActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    // MIUI also blocks the test process's background Context.startActivity.
    // Use the shell launch that was verified to work, then observe the actual
    // Activity lifecycle rather than bypassing any UI assertions.
    val descriptor = instrumentation.uiAutomation.executeShellCommand(
        "am start -n ${context.packageName}/androidx.activity.ComponentActivity"
    )
    descriptor.use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
    val deadline = SystemClock.uptimeMillis() + 10_000L
    while (SystemClock.uptimeMillis() < deadline) {
        var activity: ComponentActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it.javaClass == ComponentActivity::class.java } as? ComponentActivity
        }
        activity?.let { return it }
        SystemClock.sleep(50L)
    }
    throw AssertionError("Compose test host did not reach RESUMED within 10 seconds")
}
