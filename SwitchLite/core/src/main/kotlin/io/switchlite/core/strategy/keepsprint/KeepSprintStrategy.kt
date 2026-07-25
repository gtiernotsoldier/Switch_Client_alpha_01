package io.switchlite.core.strategy.keepsprint

import io.switchlite.core.strategy.Strategy
import io.switchlite.core.strategy.StrategyContext

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
    val cooldownTicks: Int
)

/**
 * Result of a KeepSprint decision.
 * The adapter maps this to platform actions (set sprinting, modify motion).
 */
sealed class KeepSprintResult {
    /** Restore sprint at the given horizontal keep percentage. */
    data class Restore(val keepPercentage: Float) : KeepSprintResult()

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
 * @property sprintJustCancelled True when sprint was true last tick, now false, and attack key is held.
 * @property targetHurtTime The current target's hurtTime (0-10), or null if no target.
 * @property targetDistance Horizontal distance to target in blocks, or null if no target.
 * @property currentTick Current game tick.
 */
data class KeepSprintInput(
    /** True when sprint was true last tick and is now false while attack key is held — detects vanilla's attack→cancelSprint. */
    val sprintJustCancelled: Boolean,
    val targetHurtTime: Int?,
    val targetDistance: Float?,
    val currentTick: Int
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
                return KeepSprintResult.Restore(keepPct)
            }
        }

        // 2. Only activate when sprint was just cancelled by an attack
        if (!ksInput.sprintJustCancelled) return KeepSprintResult.Pass

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
            KeepSprintResult.Restore(keepPercentage)
        }
    }
}
