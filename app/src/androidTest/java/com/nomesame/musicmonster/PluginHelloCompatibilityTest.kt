package com.nomesame.musicmonster

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.plugin.PluginProbe
import com.nomesame.musicmonster.plugin.PluginRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Intentionally uses only the original v1 API, so this same test APK can exercise an older host. */
@RunWith(AndroidJUnit4::class)
class PluginHelloCompatibilityTest {
    @Test fun originalHelloContractStillWorksAcrossApkUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = PluginRegistry(context).available().firstOrNull()
            ?: throw AssertionError("Same-signed plugin required")
        val finished = CountDownLatch(1)
        var result: PluginProbe.Result? = null
        lateinit var probe: PluginProbe
        instrumentation.runOnMainSync {
            probe = PluginProbe(context)
            probe.probe(component) { result = it; finished.countDown() }
        }
        try {
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals(PluginProbe.Result.READY, result)
        } finally { instrumentation.runOnMainSync { probe.close() } }
    }
}
