package com.nomesame.musicmonster

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.plugin.PluginProbe
import com.nomesame.musicmonster.plugin.PluginRegistry
import com.nomesame.musicmonster.plugin.api.PluginProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportCapabilityCompatibilityTest {
    @Test fun installedApkEnablesOnlyItsNegotiatedCapability() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = InstrumentationRegistry.getArguments().getString("expectedPlugin") ?: "import"
        val component = PluginRegistry(instrumentation.targetContext).available().firstOrNull()
        if (expected == "absent") { assertNull(component); return }
        assertNotNull(component)
        val finished = CountDownLatch(1)
        var result: PluginProbe.CapabilityResult? = null
        lateinit var probe: PluginProbe
        instrumentation.runOnMainSync {
            probe = PluginProbe(instrumentation.targetContext)
            probe.probeCapabilities(requireNotNull(component)) { result = it; finished.countDown() }
        }
        try {
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals(PluginProbe.Result.READY, result!!.result)
            assertEquals(expected == "import",
                PluginProtocol.hasCapability(result!!.capabilities, PluginProtocol.CAP_IMPORT))
        } finally { instrumentation.runOnMainSync { probe.close() } }
    }
}
