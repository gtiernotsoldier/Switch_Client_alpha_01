package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * KeepSprint — keeps sprint speed while attacking.
 *
 * Vanilla MC reduces horizontal speed when attacking (to ~60%). This module re-applies the
 * desired keep percentage so your speed doesn't drop — i.e. you "keep sprint speed" while
 * fighting.
 *
 * Trigger (two styles, like SuperKnockback):
 *   - RisingEdge (default): fire on a fresh hit (hurtTime rising edge). Reliable, never misses.
 *   - HurtTimeExact: fire when target.hurtTime == HurtTime (tunable; exact frame can be missed
 *     under 20Hz sampling).
 * Plus optional Delay (ms before the keep applies) and HitCount (fire once every N hits).
 *
 * Modes:
 * - Normal: always keep to [horizontalKeep] (1.0 = full sprint speed kept).
 * - Legit: interpolate the keep factor by distance to target (closer = more conservative).
 *
 * Chance lets the keep apply probabilistically (more covert).
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ========== Speed ==========
    private val horizontalKeep by float("HorizontalKeep", 1.0f, 0.6f..1.0f)

    // ========== Legit Mode: Distance-based interpolation ==========
    private val minReach by float("MinReach", 1.0f, 0f..1.5f, "blocks")
    private val maxReach by float("MaxReach", 3.0f, 2.5f..3.0f, "blocks")
    private val minKeep by float("MinKeep", 0.65f, 0.6f..0.7f)
    private val maxKeep by float("MaxKeep", 0.85f, 0.7f..0.95f)

    // ========== Probability ==========
    private val chance by probability("Chance", 100, 0..100)

    // ========== Trigger (like SuperKnockback) ==========
    private val triggerMode by choices("TriggerMode", arrayOf("RisingEdge", "HurtTimeExact"))
    private val hurtTime by int("HurtTime", 10, 0..10)
    private val delay by int("Delay", 0, 0..500, "ms")
    private val hitCount by int("HitCount", 1, 1..20, "hits")

    // ========== Unified Condition Engine ==========
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@KeepSprint.onlyGround
        onlyMove = this@KeepSprint.onlyMove
        onlyMoveForward = this@KeepSprint.onlyMoveForward
        onlyWhenTargetGoesBack = this@KeepSprint.onlyWhenTargetGoesBack
    }

    // ========== State ==========
    @Volatile private var lastPlayer: PlayerState? = null
    private var hitCounter = 0
    private var delayEndNano = 0L
    private var delayPending = false
    private var lastTargetId = -1

    // RisingEdge fresh-hit detection (set by the attack listener).
    private var hitPending = false

    // HurtTimeExact fresh-hit detection (rising-edge of hurtTime, evaluated in tick).
    private var prevHurt = false

    private val attackListener: (TargetState?) -> Unit = { if (enabled) hitPending = true }
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t -> if (enabled) onTick(p, t) }

    private fun onTick(player: PlayerState, target: TargetState?) {
        lastPlayer = player

        // Apply delayed keep when the timer expires.
        if (delayPending) {
            if (System.nanoTime() >= delayEndNano) {
                delayPending = false
                applyKeep(player, target)
            }
            return
        }

        // Reset edge state when no target / target changes.
        if (target == null) {
            hitPending = false
            prevHurt = false
            lastTargetId = -1
            return
        }
        if (lastTargetId != target.entityId) {
            lastTargetId = target.entityId
            hitPending = false
            prevHurt = false
            hitCounter = 0
        }

        // Determine whether this tick is a trigger.
        val triggered = when (triggerMode) {
            "HurtTimeExact" -> {
                val isHurt = target.hurtTime > 0
                val fresh = isHurt && !prevHurt
                prevHurt = isHurt
                // For HurtTimeExact we fire when hurtTime == HurtTime; otherwise we treat the
                // rising edge as the start and only fire at the configured value.
                if (isHurt && !fresh && target.hurtTime == hurtTime) true else false
            }
            else -> { // RisingEdge
                if (hitPending) { hitPending = false; true } else false
            }
        }
        if (!triggered) return

        if (!ConditionChecker.check(triggerOptions, player, target)) return
        if (chance.current < 100 && Random.nextInt(100) >= chance.current) return

        // Hit-count throttle: keep once every `hitCount` hits.
        hitCounter++
        if (hitCounter < hitCount) return
        hitCounter = 0

        // Delay or immediate keep.
        if (delay > 0) {
            delayEndNano = System.nanoTime() + delay * 1_000_000L
            delayPending = true
        } else {
            applyKeep(player, target)
        }
    }

    /** Apply the keep factor to the player's horizontal motion. */
    private fun applyKeep(player: PlayerState, target: TargetState?) {
        if (player === io.switchlite.core.model.PlayerState.EMPTY) return
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        val keepFactor = when (mode) {
            "Legit" -> computeLegitKeep(target)
            else -> horizontalKeep
        }

        val currentSpeed = sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ)
        if (currentSpeed < 0.001) return

        EventBridge.applyMotion(
            Vec3(player.motionX * keepFactor, player.motionY, player.motionZ * keepFactor)
        )
        io.switchlite.core.logging.CoreLogger.debug(
            "[KeepSprint] Kept speed at ${"%.0f".format(keepFactor * 100)}% (mode=$mode)"
        )
    }

    /** Legit: interpolate keep factor by target distance (closer = more conservative). */
    private fun computeLegitKeep(target: TargetState?): Float {
        val dist = target?.distance ?: return horizontalKeep
        val minR = minReach
        val maxR = maxReach
        if (dist <= minR) return minKeep
        if (dist >= maxR) return maxKeep
        val t = (dist - minR) / (maxR - minR)
        return minKeep + (maxKeep - minKeep) * t
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerAttackListener(attackListener)
        EventBridge.registerTickListener(tickListener)
        lastPlayer = null
        hitCounter = 0
        delayPending = false
        hitPending = false
        prevHurt = false
        lastTargetId = -1
    }

    override fun onDisable() {
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterTickListener(tickListener)
        lastPlayer = null
        hitCounter = 0
        delayPending = false
        hitPending = false
        prevHurt = false
        lastTargetId = -1
    }
}
