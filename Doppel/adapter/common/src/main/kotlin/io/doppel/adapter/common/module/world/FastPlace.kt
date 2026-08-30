package io.doppel.adapter.common.module.world

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int

/**
 * FastPlace — reduce or eliminate block placement delay.
 *
 * Vanilla imposes a 4-tick cooldown (rightClickDelayTimer) after placing
 * a block. FastPlace caps this timer to the configured speed value.
 *
 * Speed 0 = off (vanilla 4-tick). 1-4 = reduced tick delay.
 * OnlyBlocks restricts to block placement (skips item use).
 * OnlyWhenFacingBlocks skips when crosshair isn't on a block.
 */
object FastPlace : Module("FastPlace", Category.WORLD) {

    private val speed by int("Speed", 0, 0..4, "ticks")
    private val onlyBlocks by boolean("OnlyBlocks", true)
    private val onlyWhenFacingBlocks by boolean("OnlyWhenFacingBlocks", true)

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        if (speed == 0) return
        if (onlyBlocks && player.isUsingItem) return
        if (onlyWhenFacingBlocks && !EventBridge.isLookingAtBlock) return
        EventBridge.setRightClickDelay(speed)
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
