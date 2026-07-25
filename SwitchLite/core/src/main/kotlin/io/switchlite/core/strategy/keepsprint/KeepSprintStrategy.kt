package io.switchlite.core.strategy.keepsprint

import io.switchlite.core.strategy.Strategy
import io.switchlite.core.strategy.StrategyContext
import io.switchlite.core.util.Vec3
import kotlin.math.sqrt

/**
 * Configuration snapshot for KeepSprint.
 *
 * @property mode "Normal" or "Legit".
 * @property horizontalKeep Percentage of horizontal sprint speed to restore (0.6 = vanilla, 1.0 = full).
 * @property minReach (Legit only) Below this distance, use [minKeep] percentage.
 * @property maxReach (Legit only) Above this distance, use [maxKeep] percentage.
 * @property minKeep (Legit only) Keep percentage at close range.
 * @property maxKeep (Legit only) Keep percentage at far range.
 * @property chance 0-100 probability of activation per attack.
 * @property hurtTimeMax Only activate when target's hurtTime <= this value.
 * @property delayTicks Ticks to wait after attack before restoring sprint.
 * @property cooldownTicks Minimum ticks between activations.
 * @property sprintBaseSpeed Platform-specific sprint base speed (e.g. 0.286 for 1.8.9). Injected by adapter.
 */
data class KeepSprintConfig(
    val mode: String,
    val horizontalKeep: Float,
    val minReach: Float,
    val maxReach: Float,
    val minKeep: Float,
    val maxKeep: Float,
    val chance: Int,
    val hurtTimeMax: Int,
    val delayTicks: Int,
    val cooldownTicks: Int,
    val sprintBaseSpeed: Double = 0.286
)

/**
 * Result of a KeepSprint decision.
 * The adapter maps this to platform actions (set sprinting, apply motion).
 */
sealed class KeepSprintResult {
    /** Restore sprint and apply this motion vector (null = skip motion, just set flag). */
    data class Restore(val keepPercentage: Float, val motion: Vec3?) : KeepSprintResult()

    /** Delayed restore — store the percentage, adapter applies after delayTicks. */
    data class DelayedRestore(val keepPercentage: Float, val releaseTick: Int) : KeepSprintResult()

    /** Do nothing this tick. */
    object Pass : KeepSprintResult()
}

/**
 * Mutable per-session state for KeepSprint.
 */
class KeepSprintState(
    var lastActivationTick: Int = -999,
    var pendingRestore: Pair<Float, Int>? = null  // (keepPercentage, releaseTick)
) : StrategyContext {
    override fun reset() {
        lastActivationTick = -999
        pendingRestore = null
    }
}

/**
 * Input for KeepSprint strategy execution.
 * Pure data — the adapter assembles this from platform state.
 *
 * @property sprintCancelledTick The tick when sprint was cancelled by an attack, or null.
 * @property targetHurtTime The current target's hurtTime (0-10), or null if no target.
 * @property targetDistance Horizontal distance to target in blocks, or null if no target.
 * @property currentTick Current game tick.
 * @property motionX Player's current motionX (for core-layer motion computation).
 * @property motionY Player's current motionY.
 * @property motionZ Player's current motionZ.
 */
data class KeepSprintInput(
    val sprintCancelledTick: Int?,
    val targetHurtTime: Int?,
    val targetDistance: Float?,
    val currentTick: Int,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double
)

/**
 * KeepSprint strategy — decides whether and how to restore sprint after attacking.
 *
 * Normal mode: always restore to [KeepSprintConfig.horizontalKeep] percentage.
 * Legit mode: interpolate keep percentage based on distance to target.
 *
 * Core layer: zero platform dependencies, pure math + config evaluation.
 */
object KeepSprintStrategy : Strategy<KeepSprintConfig, KeepSprintState, KeepSprintResult> {

    override val name: String = "KeepSprint"

    override fun execute(config: KeepSprintConfig, state: KeepSprintState, input: Any): KeepSprintResult {
        val ksInput = input as? KeepSprintInput ?: return KeepSprintResult.Pass

        // 1. Check pending delayed restore
        state.pendingRestore?.let { (keepPct, releaseTick) ->
            if (ksInput.currentTick >= releaseTick) {
                state.pendingRestore = null
                return KeepSprintResult.Restore(keepPct, computeMotion(config, keepPct, ksInput))
            }
        }

        // 2. Only activate within the sprint-cancel window (max 10 ticks, matching MC hurtTime range)
        val cancelAge = ksInput.sprintCancelledTick?.let { ksInput.currentTick - it } ?: return KeepSprintResult.Pass
        if (cancelAge > 10) return KeepSprintResult.Pass

        // 3. HurtTime check
        val hurtTime = ksInput.targetHurtTime ?: return KeepSprintResult.Pass
        if (hurtTime > config.hurtTimeMax) return KeepSprintResult.Pass

        // 4. Cooldown check
        if (ksInput.currentTick - state.lastActivationTick < config.cooldownTicks) {
            return KeepSprintResult.Pass
        }

        // 5. Chance check
        if (config.chance < 100) {
            val roll = kotlin.random.Random.nextInt(100)
            if (roll >= config.chance) return KeepSprintResult.Pass
        }

        // 6. Calculate keep percentage based on mode
        val keepPercentage = when (config.mode) {
            "Legit" -> LegitKeepSprintStrategy.calculateKeep(config, ksInput.targetDistance)
            else -> config.horizontalKeep  // Normal
        }

        // 7. Record activation
        state.lastActivationTick = ksInput.currentTick

        // 8. Delay or immediate restore
        return if (config.delayTicks > 0) {
            val releaseTick = ksInput.currentTick + config.delayTicks
            state.pendingRestore = keepPercentage to releaseTick
            KeepSprintResult.DelayedRestore(keepPercentage, releaseTick)
        } else {
            KeepSprintResult.Restore(keepPercentage, computeMotion(config, keepPercentage, ksInput))
        }
    }

    /**
     * Compute the target motion vector: scale current horizontal motion
     * so its magnitude equals sprintBaseSpeed * keepPercentage.
     * Returns null if current speed is negligible (player is stationary).
     */
    private fun computeMotion(config: KeepSprintConfig, keepPercentage: Float, input: KeepSprintInput): Vec3? {
        val currentSpeed = sqrt(input.motionX * input.motionX + input.motionZ * input.motionZ)
        if (currentSpeed < 0.001) return null

        val targetSpeed = config.sprintBaseSpeed * keepPercentage
        val scale = targetSpeed / currentSpeed
        return Vec3(input.motionX * scale, input.motionY, input.motionZ * scale)
    }
}
