package io.switchlite.adapter.common.module.render

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * NoFOV — disables dynamic FOV changes from sprinting and speed effects.
 *
 * Sets the FOV modifier to base value every tick, preventing the vanilla
 * FOV expansion during sprint and speed potions. No configuration.
 */
object NoFOV : Module("NoFOV", Category.RENDER) {

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) EventBridge.resetFovModifier()
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
