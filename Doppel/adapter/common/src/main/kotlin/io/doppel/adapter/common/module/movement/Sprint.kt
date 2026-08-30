package io.doppel.adapter.common.module.movement

import io.doppel.core.condition.ConditionChecker
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.triggerOptions

/**
 * Sprint — vanilla sprint state manager (Movement category).
 *
 * Auto-enables sprint when the player is moving forward and on ground,
 * mimicking vanilla sprint mechanics (double-tap W or auto-sprint).
 *
 * Vanilla sprint cancellation / prevention:
 *   - Sprint cancels in water/lava/web (EventBridge.isInFluid)
 *   - Sprint cancels when food level <= 6 (EventBridge.foodLevel)
 *   - Sprint blocked while blocking/using item (player.isBlocking/isUsingItem)
 *   - Attack cancellation intentionally NOT handled (KeepSprint owns that)
 *
 * Compatible with SprintReset, WTap/STap, SuperKnockback, KeepSprint —
 * all call EventBridge.setSprinting() with no cross-module conflict.
 */
object Sprint : Module("Sprint", Category.MOVEMENT) {

    private val onlyGround by boolean("OnlyGround", true)
    private val onlyMoveForward by boolean("OnlyMoveForward", true)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@Sprint.onlyGround
        onlyMoveForward = this@Sprint.onlyMoveForward
    }

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        if (!player.isMovingForward) return
        if (!ConditionChecker.check(triggerOptions, player, null)) return
        if (EventBridge.isInFluid || EventBridge.foodLevel <= 6) return
        // Vanilla: can't sprint while blocking or using item
        if (player.isBlocking || player.isUsingItem) return
        if (!player.isSprinting) {
            EventBridge.setSprinting(true)
        }
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
