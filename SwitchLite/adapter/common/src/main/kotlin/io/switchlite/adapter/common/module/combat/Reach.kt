package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.combat.CombatTrigger
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions

/**
 * Reach — dynamically extend attack range on trigger.
 *
 * Uses CombatTrigger EQUAL mode: when target.hurtTime reaches maxHurt
 * (attack just landed), counts toward attack threshold. On fire, extends
 * reach via EventBridge.setReach(). Fabric and Forge both supported.
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
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1
    private var delayNano: Long = 0L
    private var extending: Boolean = false

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
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

        // CombatTrigger EQUAL mode: hurtTime match + hit counting + probability
        val eval = CombatTrigger.evaluate(
            mode = CombatTrigger.Mode.EQUAL,
            target = target,
            maxHurtTime = player.maxHurtResistantTime,
            hitCounter = hitCounter,
            hitThreshold = hitThreshold,
            hitPerMin = hitPer,
            hitPerMax = hitPer,
            chance = chance
        )
        hitCounter = eval.hitCounter
        hitThreshold = eval.hitThreshold
        if (!eval.fire) return

        // Fire — extend reach for [delay] ms, then fall back to min
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
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        extending = false
    }
}
