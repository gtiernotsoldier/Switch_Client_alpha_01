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
 * desired keep percentage on every fresh hit so your speed doesn't drop — i.e. you "keep sprint
 * speed" while fighting.
 *
 * Trigger: fresh hit of the crosshair target (hurtTime rising edge — our reliable 20Hz
 * equivalent of Raven's attack event). On each hit we scale the player's horizontal motion by
 * the keep factor. Unlike the old implementation this does NOT depend on detecting a sprint
 * cancel (which 1.8.9 rarely produces), so it actually fires.
 *
 * Modes:
 * - Normal: always keep to [horizontalKeep] (1.0 = full sprint speed kept).
 * - Legit: interpolate the keep factor by distance to target (closer = more conservative,
 *   farther = higher keep), which also keeps a benefit while looking more natural.
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
    // Track current player position/time to compute the sprint reference is not needed here;
    // we just scale existing motion. But we keep the last player snapshot for distance checks.
    @Volatile private var lastPlayer: PlayerState? = null

    private val attackListener: (TargetState?) -> Unit = { t ->
        if (enabled) onHit(t)
    }
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) lastPlayer = p
    }

    /**
     * Called on a fresh hit (hurtTime rising edge via EventBridge.notifyAttack).
     * Applies the keep factor to the player's horizontal motion.
     */
    private fun onHit(target: TargetState?) {
        val player = lastPlayer ?: return
        if (!ConditionChecker.check(triggerOptions, player, target)) return
        if (chance.current < 100 && Random.nextInt(100) >= chance.current) return

        // Sprinting is not strictly required; if not sprinting there's little to keep, but we
        // still allow a light keep so the benefit applies in both modes. Compute the keep factor.
        val keepFactor = when (mode) {
            "Legit" -> computeLegitKeep(target)
            else -> horizontalKeep
        }

        val currentSpeed = sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ)
        if (currentSpeed < 0.001) return

        // Scale current horizontal motion toward keepFactor (1.0 = keep full speed).
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
    }

    override fun onDisable() {
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterTickListener(tickListener)
        lastPlayer = null
    }
}
