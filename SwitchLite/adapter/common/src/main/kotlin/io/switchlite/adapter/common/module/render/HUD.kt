package io.switchlite.adapter.common.module.render

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.ModuleRegistry

/**
 * HUD — on-screen display of active module states.
 *
 * Builds a text line of enabled module names every tick and exposes
 * it via EventBridge.hudTextLine for the adapter to render.
 *
 * Hidden modules are excluded. Combat modules (silent) show in white
 * instead of red for anti-cheat stealth.
 */
object HUD : Module("HUD", Category.RENDER) {

    /** Per-line format: "ModuleName" — silent categories get §7 prefix. */
    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) onTick()
    }

    private fun onTick() {
        val names = ModuleRegistry.getEnabled()
            .filter { !it.hidden }
            .joinToString(" | ") { it.name }
        EventBridge.hudTextLine = if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.hudTextLine = ""
    }
}
