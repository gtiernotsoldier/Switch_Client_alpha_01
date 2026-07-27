package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.triggerOptions

/**
 * Sprint — vanilla sprint state manager.
 *
 * Auto-enables sprint when the player is moving forward and on ground,
 * mimicking vanilla sprint mechanics (double-tap W or auto-sprint).
 *
 * Compatibility:
 *   SprintReset — reads sprint state, this module provides the baseline.
 *   WTap/STap — temporarily release W / press S; sprint re-enables after tap.
 *   SuperKnockback — toggles sprint off/on; this module restores sprint after.
 *   KeepSprint — restores sprint after attack slowdown; Sprint covers the gap
 *   between attacks.
 *
 * All four modules call setSprinting() via EventBridge — no direct conflict.
 */
object Sprint : Module("Sprint", Category.COMBAT) {

    // ========== Conditions ==========
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyMoveForward by boolean("OnlyMoveForward", true)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@Sprint.onlyGround
        onlyMoveForward = this@Sprint.onlyMoveForward
    }

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Must be moving forward
        if (!player.isMovingForward) return

        // Condition check (ground + forward)
        if (!ConditionChecker.check(triggerOptions, player, null)) return

        // Vanilla sprint cancellation: fluid or hunger <= 6
        if (EventBridge.isInFluid || EventBridge.foodLevel <= 6) return

        // Enable sprint if not already sprinting
        if (!player.isSprinting) {
            EventBridge.setSprinting(true)
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
