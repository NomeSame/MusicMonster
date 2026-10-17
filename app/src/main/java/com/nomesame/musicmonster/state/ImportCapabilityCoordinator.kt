package com.nomesame.musicmonster.state

import android.content.ComponentName
import com.nomesame.musicmonster.plugin.PluginProbe

/**
 * Bridges one fresh capability negotiation into the [ImportController].
 *
 * Layer: **State / Coordinator** — it owns the one-shot probe lifecycle but holds no binder,
 * no wire details and no strings. The capability answer is never stored: every [refresh]
 * renegotiates, so removing the plugin or swapping in a HELLO-only build hides the import entry
 * on the next activity start (DECISIONS.md §4). [close] releases the binding.
 *
 * The seams ([components], [newProbe]) keep the decision testable without a real plugin.
 */
class ImportCapabilityCoordinator(
    private val controller: ImportController,
    private val components: () -> List<ComponentName>,
    private val newProbe: () -> PluginProbe,
) {

    private var probe: PluginProbe? = null

    /**
     * Probes the first discovery-checked component and reports the negotiated bitmask to the
     * controller, then lets it re-attach to a running job. With **no** component at all the
     * capability is reported as `0` immediately, so the import entry stays hidden (fail-closed).
     */
    fun refresh() {
        probe?.close()
        probe = null
        val component = components().firstOrNull()
        if (component == null) {
            controller.onCapabilities(0)
            return
        }
        val created = newProbe()
        probe = created
        created.probeCapabilities(component) { result ->
            // A stale probe (closed by close()/a newer refresh) must not overwrite the state.
            if (probe !== created) return@probeCapabilities
            controller.onCapabilities(result.capabilities)
            controller.reattach()
        }
    }

    /** Releases the probe binding; safe to call repeatedly and never changes the player. */
    fun close() {
        probe?.close()
        probe = null
    }
}
