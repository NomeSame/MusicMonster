package com.nomesame.musicmonster

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.FileInputStream

/** Observe real lifecycle transitions with deadlines; MIUI can hang startActivitySync indefinitely. */
fun launchComposeHost(): ComponentActivity = launchTestActivity(ComponentActivity::class.java)

fun <T : ComponentActivity> launchTestActivity(type: Class<T>): T {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val descriptor = instrumentation.uiAutomation.executeShellCommand(
        "am start -n ${context.packageName}/${type.name}"
    )
    descriptor.use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
    return awaitResumedTestActivity(type)
}

fun <T : ComponentActivity> awaitResumedTestActivity(type: Class<T>, previous: T? = null): T {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + 10_000L
    while (SystemClock.uptimeMillis() < deadline) {
        var activity: T? = null
        instrumentation.runOnMainSync {
            val candidate = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it.javaClass == type && it !== previous }
            if (candidate != null) activity = type.cast(candidate)
        }
        activity?.let { return it }
        SystemClock.sleep(50L)
    }
    throw AssertionError("${type.simpleName} did not reach RESUMED within 10 seconds")
}
