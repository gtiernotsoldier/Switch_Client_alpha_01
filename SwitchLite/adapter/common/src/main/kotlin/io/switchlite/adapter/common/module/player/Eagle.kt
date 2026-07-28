package io.switchlite.adapter.common.module.player

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import kotlin.random.Random

/**
 * Eagle — auto-sneak at block edges to prevent falling.
 *
 * When the player stands on a block edge (block below is air), automatically
 * presses the Shift key to sneak. After leaving the edge, holds sneak for a
 * random duration (MaxSneakTime 1-5 ticks) before releasing — preventing
 * instant un-sneak drops.
 *
 * OnlyWhenLookingDown: only trigger when pitch >= threshold (default 45°).
 * Randomised release timing reduces anti-cheat "mechanical" detection.
 */
object Eagle : Module("Eagle", Category.PLAYER) {

    private val maxSneakTimeMin by int("MaxSneakTimeMin", 1, 1..5, "ticks")
    private val maxSneakTimeMax by int("MaxSneakTimeMax", 5, 1..5, "ticks")
    private val onlyWhenLookingDown by boolean("OnlyWhenLookingDown", false)
    private val lookDownThreshold by int("LookDownThreshold", 45, 0..90, "°")

    private var sneakTicks: Int = 0
    private var releaseAfterTicks: Int = 0

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Don't override manual sneak
        if (player.isSneaking && sneakTicks == 0) return

        // Edge check
        val onEdge = EventBridge.isOnBlockEdge()

        // Look-down gate
        val lookOk = !onlyWhenLookingDown ||
            player.rotation.pitch >= lookDownThreshold

        if (onEdge && lookOk) {
            // On edge → press sneak
            EventBridge.pressSneak()
            sneakTicks = 0
            releaseAfterTicks = Random.nextInt(maxSneakTimeMin, maxSneakTimeMax + 1)
        } else if (sneakTicks > 0 || !onEdge) {
            // Not on edge → count then release
            sneakTicks++
            if (sneakTicks >= releaseAfterTicks) {
                EventBridge.releaseSneak()
                sneakTicks = 0
            }
        }
    }

    override fun onEnable() {
        sneakTicks = 0
        releaseAfterTicks = 0
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.releaseSneak()
        sneakTicks = 0
    }
}
