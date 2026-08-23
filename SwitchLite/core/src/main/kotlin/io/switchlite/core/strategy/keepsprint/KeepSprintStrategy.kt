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

    /**
     * Vanilla 1.8.9 attack slowdown: attacking multiplies horizontal motion by this amount.
     * The constant we must compensate for to "keep sprint" (Raven's onAttackTargetEntityWithCurrentItem
     * inserts a factor here; the default Slow% of 40 equals this 0.6).
     */
    const val VANILLA_ATTACK_SLOWDOWN = 0.6f

    override val name: String = "KeepSprint"

    /**
     * Probability gate — rolls once per attack action. Returns true when the attack should be kept
     * (keep sprint that swing). Pure math, no platform deps.
     */
    fun shouldActivate(config: KeepSprintConfig): Boolean =
        config.chance >= 100 || kotlin.random.Random.nextInt(100) < config.chance

    /**
     * The keep percentage to preserve for this attack — the fraction of the player's natural sprint
     * speed to keep after the attack slowdown is compensated.
     *   - Normal: fixed [KeepSprintConfig.horizontalKeep] (1.0 = keep full sprint speed).
     *   - Legit: no target required — a *simulated* distance in [minReach, maxReach] is interpolated
     *     to a keep percentage in [minKeep, maxKeep] (closer = more conservative / slower). The caller
     *     supplies the random distance, so each attack's keep looks slightly different.
     */
    fun keepPercentage(config: KeepSprintConfig, mode: String, simulatedDistance: Float): Float =
        when (mode) {
            "Legit" -> LegitKeepSprintStrategy.calculateKeep(config, simulatedDistance)
            else -> config.horizontalKeep
        }

    /**
     * The multiplier to apply to the player's horizontal motion immediately after an attack so the
     * vanilla slowdown doesn't reduce his speed. `horizontalKeep` is the target fraction of the
     * pre-attack speed to preserve (1.0 = keep full speed), so we divide by the slowdown.
     * Pure math — zero platform dependencies.
     */
    fun compensateFactor(config: KeepSprintConfig): Float =
        config.horizontalKeep / VANILLA_ATTACK_SLOWDOWN

    /**
     * Compounding-proof restore: scale the player's current horizontal motion so its magnitude
     * equals [targetHorizontalSpeed] (e.g. sprintBaseSpeed * keepFactor), preserving direction.
     * Returns null when the player is already at/above target (nothing to restore) or essentially
     * stationary — so calling this every frame while attacking can never overshoot, unlike a raw
     * `motion * factor` multiply which compounds across frames with no swing.
     */
    fun restoreToTargetSpeed(
        motionX: Double,
        motionY: Double,
        motionZ: Double,
        targetHorizontalSpeed: Double
    ): Vec3? {
        val current = sqrt(motionX * motionX + motionZ * motionZ)
        if (current < 0.001) return null
        if (current >= targetHorizontalSpeed) return null // already at/above target — nothing to restore
        val scale = targetHorizontalSpeed / current
        return Vec3(motionX * scale, motionY, motionZ * scale)
    }

    /**
     * Pure algorithm: compute the keep-factor and the resulting scaled horizontal motion vector,
     * given the mode, target distance, and current player motion. Zero platform dependencies.
     *
     * @param mode "Normal" or "Legit".
     * @param distance target distance in blocks, or null.
     * @param motionX/motionY/motionZ current player motion.
     * @return [KeepResult] containing the applied keep factor and the motion to apply (or null
     *         when the player is essentially stationary).
     */
    data class KeepResult(val keepFactor: Float, val motion: Vec3?)

    fun computeKeepMotion(
        config: KeepSprintConfig,
        mode: String,
        distance: Float?,
        motionX: Double,
        motionY: Double,
        motionZ: Double
    ): KeepResult {
        val keepFactor = when (mode) {
            "Legit" -> LegitKeepSprintStrategy.calculateKeep(config, distance)
            else -> config.horizontalKeep
        }
        val currentSpeed = sqrt(motionX * motionX + motionZ * motionZ)
        if (currentSpeed < 0.001) {
            return KeepResult(keepFactor, null)
        }
        // Scale current horizontal motion by the keep factor (1.0 = keep full speed).
        return KeepResult(
            keepFactor,
            Vec3(motionX * keepFactor, motionY, motionZ * keepFactor)
        )
    }

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
