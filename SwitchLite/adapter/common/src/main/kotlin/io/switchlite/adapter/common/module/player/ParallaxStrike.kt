package io.switchlite.adapter.common.module.player

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.combat.CombatTrigger
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import kotlin.random.Random

/**
 * Parallax Strike — render-level position offset for visual advantage.
 *
 * Applies a small horizontal render offset to the player model when
 * combat conditions are met, creating visual distance misperception.
 * Combined with OnlyWhenTargetGoesBack, the enemy sees you further
 * away than you actually are — gaining first-strike advantage.
 *
 * Anti-cheat safety:
 *   Offset ≤ 0.8 blocks (below movement threshold checks)
 *   Duration ≤ 800ms (short pulse, no trend accumulation)
 *   Probability ≤ 80% (randomness prevents pattern detection)
 *   Cooldown ≥ 3s (avoids "jitter" classification)
 *   Y-axis offset forced to 0 (prevents flight detection)
 *   OnlyWhenTargetGoesBack ensures "pursuit" context only
 */
object ParallaxStrike : Module("ParallaxStrike", Category.PLAYER) {

    enum class Phase { IDLE, TRIGGER_DELAY, ACTIVE, COOLDOWN }

    // ========== Offset ==========
    private val minOffset by float("MinOffset", 0.3f, 0.0f..0.8f, "blocks")
    private val maxOffset by float("MaxOffset", 0.8f, 0.0f..0.8f, "blocks")

    // ========== Timing ==========
    private val duration by int("Duration", 400, 0..800, "ms")
    private val cooldown by int("Cooldown", 4000, 0..6000, "ms")
    private val delay by int("Delay", 75, 0..500, "ms")
    private val tickGate by int("Tick", 10, 1..20, "ticks")

    // ========== Trigger ==========
    private val hitPer by int("HitCount", 3, 1..10)
    private val chance by int("Chance", 70, 0..100, "%")

    // ========== Conditions ==========
    private val onlyPlane by boolean("OnlyPlane", false)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", true)
    private val onlyMoveForward by boolean("OnlyMoveForward", true)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", true)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = onlyPlane
        onlyCurrentView = onlyTargeting
        onlyMove = this@ParallaxStrike.onlyMove
        onlyMoveForward = this@ParallaxStrike.onlyMoveForward
        onlyWhenTargetGoesBack = this@ParallaxStrike.onlyWhenTargetGoesBack
    }

    // ========== State ==========
    private var phase = Phase.IDLE
    private var phaseEndNano: Long = 0L
    private var hitCounter: Int = 0
    private var hitThreshold: Int = 1
    private var tickCount: Int = 0
    private var lastEvalTick: Int = 0
    private var currentOffsetX: Float = 0f
    private var currentOffsetZ: Float = 0f
    private var targetLostNano: Long = 0L

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        tickCount++

        when (phase) {
            Phase.COOLDOWN -> {
                if (System.nanoTime() >= phaseEndNano) {
                    phase = Phase.IDLE
                }
                return
            }
            Phase.TRIGGER_DELAY -> {
                if (System.nanoTime() >= phaseEndNano) {
                    activate()
                }
                return
            }
            Phase.ACTIVE -> {
                // Early exit: target lost > 2s
                if (target == null || target.distance > 6.0f) {
                    if (targetLostNano == 0L) targetLostNano = System.nanoTime()
                    if (System.nanoTime() - targetLostNano > 2_000_000_000L) {
                        deactivate()
                    }
                } else {
                    targetLostNano = 0L
                }
                // Duration expired
                if (System.nanoTime() >= phaseEndNano) {
                    deactivate()
                }
                return
            }
            Phase.IDLE -> { /* evaluate below */ }
        }

        // ── IDLE evaluation ──
        if (target == null) return

        // Tick gate
        if (tickCount - lastEvalTick < tickGate) return
        lastEvalTick = tickCount

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // CombatTrigger EQUAL mode + hit counting
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

        // Compute random offset (X horizontal, Z primary-direction)
        val scale = minOffset + Random.nextFloat() * (maxOffset - minOffset)
        // Random sign on X, always positive Z (forward bias)
        currentOffsetX = scale * (if (Random.nextBoolean()) 1f else -1f) * 0.5f
        currentOffsetZ = scale * (0.7f + Random.nextFloat() * 0.3f)

        // Delay → ACTIVE
        if (delay > 0) {
            phase = Phase.TRIGGER_DELAY
            phaseEndNano = System.nanoTime() + delay * 1_000_000L
        } else {
            activate()
        }
    }

    private fun activate() {
        phase = Phase.ACTIVE
        phaseEndNano = System.nanoTime() + duration * 1_000_000L
        targetLostNano = 0L
        EventBridge.renderOffsetX = currentOffsetX
        EventBridge.renderOffsetY = 0f     // Y always zero (anti-flight)
        EventBridge.renderOffsetZ = currentOffsetZ
    }

    private fun deactivate() {
        EventBridge.clearRenderOffset()
        phase = Phase.COOLDOWN
        phaseEndNano = System.nanoTime() + cooldown * 1_000_000L
        currentOffsetX = 0f
        currentOffsetZ = 0f
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        hitThreshold = hitPer
        tickCount = 0
        lastEvalTick = 0
        phase = Phase.IDLE
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.clearRenderOffset()
        phase = Phase.IDLE
    }
}
