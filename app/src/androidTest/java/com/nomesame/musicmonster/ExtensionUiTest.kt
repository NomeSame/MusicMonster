package com.nomesame.musicmonster

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.extensions.ExtensionAction
import com.nomesame.musicmonster.ui.components.ExtensionActions
import com.nomesame.musicmonster.ui.theme.MyApplicationTheme
import org.junit.*
import org.junit.Assert.*

class ExtensionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    @Before fun setup() { host = launchComposeHost() }
    @After fun cleanup() { InstrumentationRegistry.getInstrumentation().runOnMainSync { host.finish() } }
    @Test fun absentOrRemovedExtensionHasNoActionAndAvailableActionUsesItsOwnLabel() {
        var actions by mutableStateOf(emptyList<ExtensionAction>())
        var busy by mutableStateOf(false)
        var calls = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent { MyApplicationTheme { ExtensionActions(actions, busy, false, { calls++ }, {}, {}) } }
        }
        compose.onNodeWithTag("extension_action").assertDoesNotExist()
        compose.runOnIdle { actions = listOf(ExtensionAction(ComponentName("fixture", "Producer"), "Create audio")) }
        compose.onNodeWithText("Create audio").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls); busy = true }
        compose.onNodeWithTag("extension_action").assertIsNotEnabled()
        compose.runOnIdle { actions = emptyList(); busy = false }
        compose.onNodeWithTag("extension_action").assertDoesNotExist()
    }
    @Test fun installedProducerVisibilityMatchesActualDiscovery() {
        val arguments = InstrumentationRegistry.getArguments()
        val pkg = requireNotNull(arguments.getString("extensionPackage"))
        val expected = arguments.getString("expectedExtension", "true").toBoolean()
        val actions = com.nomesame.musicmonster.extensions.ExtensionRegistry(host).actions()
        assertEquals(expected, actions.any { it.component.packageName == pkg })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.setContent { MyApplicationTheme { ExtensionActions(actions, false, false, {}, {}, {}) } }
        }
        compose.onAllNodesWithTag("extension_action").assertCountEquals(actions.size)
        if (!expected) compose.onNodeWithTag("extension_action").assertDoesNotExist()
    }
}
