package io.switchlite.adapter.common.module.movement

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * NoJumpDelay — removes vanilla jump cooldown for instant repeated jumping.
 *
 * Resets the player's jump cooldown counter to 0 every tick, allowing
 * immediate jump after landing. Does not modify jump height or speed.
 *
 * No configuration. Works on 1.8 and 1.9+.
 * Client-local only — sends no abnormal packets.
 */
object NoJumpDelay : Module("NoJumpDelay", Category.MOVEMENT) {

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) EventBridge.resetJumpDelay()
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
