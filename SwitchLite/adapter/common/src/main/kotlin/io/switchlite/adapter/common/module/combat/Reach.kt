package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.HudLineProvider
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
 * Uses a rising-edge hit detector (target hurt animation), firing reliably under 20Hz
 * sampling. On a fresh hit it extends reach via EventBridge.setReach(). Forge and Fabric
 * both supported.
 */
object Reach : Module("Reach", Category.COMBAT), HudLineProvider {

    // ========== HUD value ==========
    override fun hudValue(): String = "$reachMin-$reachMax"
    override fun hudHighlight(): Boolean = true

    // ========== Reach Distance ==========
    private val reachMin by float("Min", 3.0f, 3.0f..3.5f, "blocks")
    private val reachMax by float("Max", 3.15f, 3.0f..4.0f, "blocks")

    // ========== Trigger ==========
    private val chance by int("Chance", 100, 0..100, "%")
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
    private var delayNano: Long = 0L
    private var extending: Boolean = false

    // Rising-edge "just hit" detection (same reliable pattern as SprintReset): the target's
    // hurt animation jumps >0 on a fresh hit and is 0 between hits, so the 0 -> >0 transition
    // is caught reliably under 20Hz sampling — unlike the old exact hurtResistantTime match.
    private var prevHurt = false
    private var lastTargetId: Int = -1

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
        if (target == null) {
            prevHurt = false
            lastTargetId = -1
            return
        }

        // Reset edge state when switching targets.
        if (lastTargetId != target.entityId) {
            lastTargetId = target.entityId
            prevHurt = false
            hitCounter = 0
        }

        // OnlyPlayers
        if (onlyPlayers && target.name.isEmpty()) return

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Rising-edge "just hit": hurtTime just became > 0 (fresh hit). Reliable at 20Hz.
        val isHurt = target.hurtTime > 0
        val justHit = isHurt && !prevHurt
        prevHurt = isHurt
        if (!justHit) return

        // Hit throttle: extend every `hitPer` hits.
        hitCounter++
        if (hitCounter < hitPer) return
        hitCounter = 0

        // Probability
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // Fire — extend reach for [delay] ms, then fall back to min
        EventBridge.setReach(reachMax)

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
        prevHurt = false
        lastTargetId = -1
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        extending = false
        prevHurt = false
        lastTargetId = -1
    }
}
