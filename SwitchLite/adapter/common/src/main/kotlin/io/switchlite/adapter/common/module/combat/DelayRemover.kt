package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * DelayRemover — removes 1.8 left-click cooldown for instant click response.
 *
 * Sets Minecraft.leftClickCounter to 0 every tick, bypassing the 1.8
 * built-in 0.5s click delay. Restores 1.7-style "click on frame" behaviour.
 *
 * No configuration. 1.8 exclusive — 1.9+ replaced leftClickCounter with
 * the attack cooldown bar.
 */
object DelayRemover : Module("DelayRemover", Category.COMBAT) {

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) EventBridge.resetClickDelay()
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
