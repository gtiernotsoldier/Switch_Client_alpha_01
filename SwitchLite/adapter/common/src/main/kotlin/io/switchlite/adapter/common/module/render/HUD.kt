package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.ModuleRegistry

/**
 * HUD — on-screen display of active module states.
 *
 * Renders in ALL game states: main menu, server list, in-world, death screen.
 * Uses render listener (fires unconditionally every frame) instead of
 * tick listener (fires only in-world when PlayerState is available).
 *
 * Hidden modules are excluded. Combat category modules are shown in
 * default text — no red highlight for anti-cheat stealth.
 */
object HUD : Module("HUD", Category.RENDER) {

    private val renderListener: () -> Unit = {
        if (!enabled) return
        val names = ModuleRegistry.getEnabled()
            .filter { !it.hidden }
            .joinToString(" | ") { it.name }
        EventBridge.hudTextLine = if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
    }

    override fun onEnable() {
        EventBridge.registerRenderListener(renderListener)
    }

    override fun onDisable() {
        EventBridge.unregisterRenderListener(renderListener)
        EventBridge.hudTextLine = ""
    }
}
