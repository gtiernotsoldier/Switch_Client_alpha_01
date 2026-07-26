package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * Reach — dynamically extend attack range on trigger.
 *
 * When conditions are met and attack threshold is reached, extends the
 * player's reach to [reachMax] blocks for one frame. Otherwise falls
 * back to [reachMin]. Uses the objectMouseOver overwrite technique
 * to find entities at extended range.
 *
 * 1.8 exclusive — 1.9+ reach is server-authoritative.
 */
object Reach : Module("Reach", Category.COMBAT) {

    // ========== Reach Distance ==========
    private val reachMin by float("Min", 3.0f, 3.0f..3.5f, "blocks")
    private val reachMax by float("Max", 3.15f, 3.0f..4.0f, "blocks")

    // ========== Trigger ==========
    private val chance by int("Chance", 100, 0..100, "%")
    private val tickCooldown by int("Tick", 8, 1..20, "ticks")
    private val hitPer by int("HitPer", 1, 1..10)
    private val delay by int("Delay", 0, 0..500, "ms")

    // ========== Conditions (Unified Engine) ==========
    private val onlyPlane by boolean("OnlyPlane", true)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)
    private val onlyPlayers by boolean("OnlyPlayers", true)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyMove = this@Reach.onlyMove
        onlyMoveForward = this@Reach.onlyMoveForward
        onlyWhenTargetGoesBack = this@Reach.onlyWhenTargetGoesBack
    }

    // ========== State ==========
    private var cooldown: Int = 0
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1
    private var delayNano: Long = 0L
    private var extending: Boolean = false

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // Cooldown
        if (cooldown > 0) { cooldown-- }

        // Delay gate
        if (extending) {
            if (System.nanoTime() >= delayNano) {
                extending = false
            }
            return
        }

        // Target required
        if (target == null) return

        // OnlyPlayers
        if (onlyPlayers && target.name.isEmpty()) return

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Tick cooldown
        if (cooldown > 0) return

        // Attack counting (target.hurtTime spike = attack landed)
        if (target.hurtTime != player.maxHurtResistantTime) return
        hitCounter++
        if (hitCounter < hitThreshold) return
        hitCounter = 0
        hitThreshold = hitPer

        // Probability
        if (chance < 100 && Random.nextInt(100) >= chance) {
            EventBridge.setReach(reachMin)
            cooldown = tickCooldown
            return
        }

        // Fire — extend reach
        EventBridge.setReach(reachMax)
        cooldown = tickCooldown

        if (delay > 0) {
            delayNano = System.nanoTime() + delay * 1_000_000L
            extending = true
        } else {
            // Single-frame extend, reset next tick
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        hitThreshold = hitPer
        cooldown = 0
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.setReach(reachMin)
        cooldown = 0
        extending = false
    }
}
