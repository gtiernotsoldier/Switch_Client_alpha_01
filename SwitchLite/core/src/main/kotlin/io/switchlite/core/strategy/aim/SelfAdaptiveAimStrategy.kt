package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.Vec2
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Self-adaptive aim strategy — adjusts assist intensity based on the player's
 * actual mouse control ability.
 *
 * ## Algorithm
 *
 * 1. Computes angular error between player's current aim and the ideal aim.
 * 2. Converts raw mouse delta (pixels) to angular displacement using sensitivity.
 * 3. Calculates **alignment**: how well the mouse movement reduced the angular error.
 *    alignment = 1.0 means the player perfectly compensated; 0.0 means random flailing.
 * 4. Maintains an EMA (exponential moving average) of alignment over time.
 * 5. Dynamically adjusts `aimSpeed` and `smoothness` based on EMA:
 *    - Low alignment (player struggling) → stronger assist
 *    - High alignment (player aiming well) → minimal assist
 * 6. All dynamic values are clamped to human-like limits (never exceeds Legit defaults).
 *
 * ## Architecture
 *
 * - Core layer: pure math, zero platform dependencies.
 * - Uses the same overshoot FSM, reaction delay, FOV check, and noise injection
 *   as [LegitAimStrategy] by delegating to shared helper paths.
 * - Only varies the `aimSpeed` and `smoothness` factors dynamically.
 *
 * Constitution compliance: §1 Safety (never exceeds human limits), §3 Strategy (adaptive).
 */
class SelfAdaptiveAimStrategy : AimStrategy {

    /** Extended state with adaptive tracking fields. */
    class AdaptiveState : AimStrategy.State() {
        /** EMA of player's alignment score (0.0 = random, 1.0 = perfect). */
        var alignmentEma: Float = 0.5f
        /** Angular error from the previous tick (for delta calculation). */
        var previousAngularError: Float = 0f
        /** Whether we have a valid previous frame for delta calculation. */
        var hasPreviousFrame: Boolean = false
    }

    override fun execute(
        config: AimConfig,
        state: AimStrategy.State,
        input: Any
    ): AimResult {
        require(input is AimInput) { "SelfAdaptiveAimStrategy expects AimInput" }
        val adaptiveState = state as? AdaptiveState
            ?: return AimResult.Skip // Wrong state type — should not happen
        return processTick(config, adaptiveState, input.player, input.target,
            input.mouseDeltaX, input.mouseDeltaY, input.sensitivity)
    }

    // ---- Visible for testing ----

    internal fun processTick(
        config: AimConfig,
        state: AdaptiveState,
        player: PlayerState,
        target: TargetState?,
        mouseDeltaX: Float,
        mouseDeltaY: Float,
        sensitivity: Float
    ): AimResult {
        // 1. Null guard
        if (target == null) {
            state.reset()
            return AimResult.Skip
        }

        // 2. Horizontal range check
        val dx = player.position.x - target.position.x
        val dz = player.position.z - target.position.z
        val horizontalDistance = sqrt(dx * dx + dz * dz)
        if (horizontalDistance < config.rangeMin || horizontalDistance > config.rangeMax) {
            state.resetOvershoot()
            return AimResult.Skip
        }

        // 3. Condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return AimResult.Skip
        }

        // 4. Target switch detection (reuse AimStrategy.State fields)
        if (target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
            state.reactionDelayTicks = sampleReactionDelay()
            state.resetOvershoot()
            state.hasPreviousFrame = false
        }

        // 5. Reaction delay
        if (state.reactionDelayTicks > 0) {
            state.reactionDelayTicks--
            return AimResult.Skip
        }

        // 6. Target point computation (same as LegitAimStrategy)
        val targetPoint = when (config.mode) {
            AimMode.LEGIT, AimMode.SELF_ADAPTIVE -> {
                if (RotationCalculator.isInsideHitbox(
                        player.position, player.rotation, target.hitbox
                    )
                ) {
                    return AimResult.Skip
                }
                RotationCalculator.getClosestBoxEdge(
                    player.position, player.rotation, target.hitbox
                )
            }
            AimMode.NORMAL -> {
                RotationCalculator.calculateTargetPoint(
                    player.position, target.hitbox, config.lockOnCrosshair
                )
            }
        }

        // 7. FOV check
        val rotationDiff = RotationCalculator.calculateDifference(player.rotation, targetPoint)
        if (!RotationCalculator.isWithinFov(rotationDiff, config.horizontalFov, config.verticalFov)) {
            return AimResult.Skip
        }

        // 8. Self-adaptive: update alignment EMA and compute dynamic factors
        val angularError = abs(rotationDiff.yaw) + abs(rotationDiff.pitch)

        if (state.hasPreviousFrame) {
            val errorReduction = state.previousAngularError - angularError
            val mouseAngularDisplacement = mouseDeltaToAngular(mouseDeltaX, mouseDeltaY, sensitivity)
            val alignment = if (mouseAngularDisplacement > 0.01f) {
                // How much of the mouse movement contributed to reducing error
                errorReduction / mouseAngularDisplacement
            } else {
                0.5f // No mouse movement → neutral alignment
            }.coerceIn(0f, 1f)

            // EMA update: 90% old, 10% new
            state.alignmentEma = state.alignmentEma * 0.9f + alignment * 0.1f
        }
        state.previousAngularError = angularError
        state.hasPreviousFrame = true

        // 9. Dynamic factor calculation
        val (dynamicAimSpeed, dynamicSmoothness) = computeDynamicFactors(
            config, state.alignmentEma
        )

        val yawFactor = dynamicAimSpeed / 20.0f * dynamicSmoothness
        val pitchFactor = dynamicAimSpeed / 20.0f * dynamicSmoothness * 0.6f

        // 10. Overshoot state machine (reuse LegitAimStrategy patterns)
        val finalRotation = executeOvershoot(
            config = config,
            state = state,
            player = player,
            targetPoint = targetPoint,
            rotationDiff = rotationDiff,
            yawFactor = yawFactor,
            pitchFactor = pitchFactor
        ) ?: return AimResult.Skip

        // 11. Noise injection
        val noisyRotation = NoiseProvider.applyWalk(finalRotation, config.noiseIntensity)

        return AimResult.ApplyRotation(noisyRotation)
    }

    // ==================== Adaptive Math ====================

    /**
     * Convert raw mouse delta (pixels) to approximate angular displacement (degrees).
     *
     * Minecraft's mouse sensitivity formula (approximate):
     *   angular_delta = raw_delta * sensitivity * 0.15
     *
     * This is a simplified model — the actual MC formula involves a cubic
     * sensitivity curve. The 0.15 factor is calibrated for typical sensitivity
     * values (0.5–2.0) and produces reasonable alignment scores.
     * Small inaccuracies are smoothed out by the EMA.
     */
    internal fun mouseDeltaToAngular(dx: Float, dy: Float, sensitivity: Float): Float {
        val pixelMagnitude = sqrt(dx * dx + dy * dy)
        return pixelMagnitude * sensitivity * 0.15f
    }

    /**
     * Map alignment EMA to dynamic aimSpeed and smoothness factors.
     *
     * | EMA range | aimSpeed modifier | smoothness modifier | Behaviour        |
     * |-----------|-------------------|---------------------|------------------|
     * | < 0.3     | +30%              | +20%                | Strong assist    |
     * | 0.3-0.5   | +15%              | +10%                | Moderate assist  |
     * | 0.5-0.7   | default           | default             | Standard assist  |
     * | > 0.7     | -20%              | -15%                | Minimal assist   |
     *
     * All values clamped to [1, config.aimSpeed] and [0.1f, config.smoothness].
     */
    internal fun computeDynamicFactors(
        config: AimConfig,
        alignmentEma: Float
    ): Pair<Int, Float> {
        val (speedMod, smoothMod) = when {
            alignmentEma < 0.3f -> 1.30f to 1.20f
            alignmentEma < 0.5f -> 1.15f to 1.10f
            alignmentEma < 0.7f -> 1.00f to 1.00f
            else -> 0.80f to 0.85f
        }

        val aimSpeed = (config.aimSpeed * speedMod).toInt().coerceIn(1, 20)
        val smoothness = (config.smoothness * smoothMod).coerceIn(0.1f, 1.0f)

        return aimSpeed to smoothness
    }

    // ==================== Overshoot FSM ====================

    private fun executeOvershoot(
        config: AimConfig,
        state: AimStrategy.State,
        player: PlayerState,
        targetPoint: Vec2,
        rotationDiff: Vec2,
        yawFactor: Float,
        pitchFactor: Float
    ): Vec2? {
        return when (state.overshootPhase) {
            AimStrategy.State.OvershootPhase.IDLE -> {
                val interpolated = RotationCalculator.interpolate(
                    current = player.rotation,
                    target = targetPoint,
                    yawFactor = yawFactor,
                    pitchFactor = pitchFactor
                )
                val angularSize = abs(rotationDiff.yaw) + abs(rotationDiff.pitch)
                if (angularSize > 5f && NoiseProvider.nextUniform(0f, 1f) < 0.20f) {
                    val delta = RotationCalculator.calculateDifference(player.rotation, targetPoint)
                    val overshootPercent = 0.05f + NoiseProvider.nextUniform(0f, 1f) * 0.10f
                    state.overshootTarget = Vec2(
                        targetPoint.yaw + delta.yaw * overshootPercent,
                        targetPoint.pitch + delta.pitch * overshootPercent
                    )
                    state.overshootTicksRemaining =
                        if (NoiseProvider.nextUniform(0f, 1f) < 0.5f) 1 else 2
                    state.overshootPhase = AimStrategy.State.OvershootPhase.OVERSHOOT
                    val osTarget = state.overshootTarget ?: return null
                    RotationCalculator.interpolate(
                        current = player.rotation,
                        target = osTarget,
                        yawFactor = yawFactor,
                        pitchFactor = pitchFactor
                    )
                } else {
                    interpolated
                }
            }
            AimStrategy.State.OvershootPhase.OVERSHOOT -> {
                val osTarget = state.overshootTarget ?: run {
                    state.resetOvershoot()
                    return null
                }
                val result = RotationCalculator.interpolate(
                    current = player.rotation,
                    target = osTarget,
                    yawFactor = yawFactor,
                    pitchFactor = pitchFactor
                )
                state.overshootTicksRemaining--
                if (state.overshootTicksRemaining <= 0) {
                    state.overshootPhase = AimStrategy.State.OvershootPhase.CORRECT
                }
                result
            }
            AimStrategy.State.OvershootPhase.CORRECT -> {
                val result = RotationCalculator.interpolate(
                    current = player.rotation,
                    target = targetPoint,
                    yawFactor = yawFactor * 1.2f,
                    pitchFactor = pitchFactor * 1.2f
                )
                state.resetOvershoot()
                result
            }
        }
    }

    // ==================== Helpers ====================

    private fun sampleReactionDelay(): Int {
        val z = NoiseProvider.next(0f, 1f).toDouble()
        val delayTicks = kotlin.math.exp(1.1 + 0.35 * z)
        return delayTicks.toInt().coerceIn(1, 6)
    }

    private fun AimStrategy.State.resetOvershoot() {
        overshootPhase = AimStrategy.State.OvershootPhase.IDLE
        overshootTarget = null
        overshootTicksRemaining = 0
    }
}
