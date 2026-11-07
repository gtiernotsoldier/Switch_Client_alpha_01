package io.doppel.adapter.common.module.movement

import io.doppel.core.condition.ConditionChecker
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
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
 * Compatible with SprintReset, WTap/STap, SuperKnockback, KeepSprint — all of them now go through
 * EventBridge's claim arbitration rather than calling setSprinting() directly, so the outcome no
 * longer depends on tick order. (It used to: this module asserts sprinting every tick you hold W,
 * which silently cancelled SuperKnockback's one-tick SprintTap.)
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
        // ADDITIVE claim: "I want to be sprinting" — asserted on exactly the same condition as the
        // old direct call, so the call pattern is unchanged, but it now goes through arbitration.
        // Before, this ran AFTER SuperKnockback's SprintTap had forced sprint OFF for its one tick,
        // so the tap was eaten essentially every time. (The header comment below claimed there was
        // "no cross-module conflict"; this was one, and it was the family's hardest pair.)
        if (!player.isSprinting) {
            EventBridge.claimSprint(name, true)
        } else {
            EventBridge.releaseSprint(name)
        }
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
