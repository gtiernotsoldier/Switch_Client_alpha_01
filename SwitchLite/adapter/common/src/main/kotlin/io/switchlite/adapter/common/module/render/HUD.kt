package io.switchlite.adapter.common.module.render

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.Category

/**
 * HUD — on-screen display of active module states.
 *
 * Builds a list of enabled module entries every tick and exposes them
 * via [hudEntries] for the adapter to render. Each entry carries the
 * module name and whether it should be shown in red (active indicator).
 *
 * Rules:
 * - Hidden modules (Module.hidden=true) are excluded entirely.
 * - Modules with showRedIndicator=false are shown in default color even
 *   when the global red indicator toggle is ON.
 * - The global red indicator toggle (EventBridge.isRedIndicatorEnabled)
 *   controls whether ANY modules get the red treatment.
 */
object HUD : Module("HUD", Category.RENDER) {

    /**
     * A single HUD line entry to be rendered by the adapter.
     */
    data class HUDEntry(
        val name: String,
        /** True if this entry should be rendered in red (active indicator). */
        val isRed: Boolean
    )

    /** Current HUD entries — rebuilt every tick when enabled. */
    @Volatile
    var hudEntries: List<HUDEntry> = emptyList()
        private set

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) onTick()
    }

    private fun onTick() {
        val redEnabled = EventBridge.isRedIndicatorEnabled
        hudEntries = ModuleRegistry.getEnabled()
            .filter { it.visible }
            .map { module ->
                HUDEntry(
                    name = module.name,
                    isRed = redEnabled && module.showRedIndicator
                )
            }
        // Also set the simple text line for backward compat (adapter may use either)
        val names = hudEntries.joinToString(" | ") { entry ->
            if (entry.isRed) entry.name else entry.name
        }
        EventBridge.hudTextLine = if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.hudTextLine = ""
        hudEntries = emptyList()
    }
}
