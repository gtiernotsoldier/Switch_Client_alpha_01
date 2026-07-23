package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.Vec2
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Default LEGIT / NORMAL aim strategy with full humanization.
 *
 * Processing pipeline per tick:
 * 1. Null-target guard → Skip.
 * 2. Horizontal range check (X/Z distance) → Skip.
 * 3. Unified condition check → Skip.
 * 4. Target-switch detection → reset overshoot, sample reaction delay.
 * 5. Reaction delay countdown → Skip while > 0.
 * 6. Target point computation:
 *    - LEGIT: closest box-edge if outside hitbox; Skip if already inside.
 *    - NORMAL: center or random point within hitbox.
 * 7. FOV check → Skip if outside FOV.
 * 8. Smoothing & interpolation (separate yaw/pitch factors).
 * 9. Overshoot state machine (IDLE → OVERSHOOT → CORRECT → IDLE).
 * 10. Random-walk noise injection.
 * 11. Emit [AimResult.ApplyRotation].
 */
class LegitAimStrategy : AimStrategy {

    override fun execute(
        config: AimConfig,
        state: AimStrategy.State,
        input: Any
    ): AimResult {
        require(input is AimInput) { "LegitAimStrategy expects AimInput" }
        return processTick(config, state, input.player, input.target)
    }

    // ---- Visible for testing ----

    internal fun processTick(
        config: AimConfig,
        state: AimStrategy.State,
        player: PlayerState,
        target: TargetState?
    ): AimResult {
        // 1. Null guard
        if (target == null) {
            state.reset()
            return AimResult.Skip
        }

        // 2. Horizontal range check (X/Z only, ignore height)
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

        // 4. Target switch detection
        if (target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
            state.reactionDelayTicks = sampleReactionDelay()
            state.resetOvershoot()
        }

        // 5. Reaction delay
        if (state.reactionDelayTicks > 0) {
            state.reactionDelayTicks--
            return AimResult.Skip
        }

        // 6. Target point computation
        val targetPoint = when (config.mode) {
            AimMode.LEGIT -> {
                if (RotationCalculator.isInsideHitbox(
                        player.position, player.rotation, target.hitbox
                    )
                ) {
                    // Already inside hitbox — human-like hesitation
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

        // 8. Smoothing factors
        val yawFactor = config.aimSpeed / 20.0f * config.smoothness
        val pitchFactor = config.aimSpeed / 20.0f * config.smoothness * 0.6f

        // 9. Overshoot state machine
        val finalRotation = executeOvershoot(
            config = config,
            state = state,
            player = player,
            targetPoint = targetPoint,
            rotationDiff = rotationDiff,
            yawFactor = yawFactor,
            pitchFactor = pitchFactor
        ) ?: return AimResult.Skip

        // 10. Noise injection
        val noisyRotation = NoiseProvider.applyWalk(finalRotation, config.noiseIntensity)

        return AimResult.ApplyRotation(noisyRotation)
    }

    // ---- Overshoot state machine ----

    /**
     * Runs the three-phase overshoot FSM and returns the interpolated
     * rotation for this tick, or null if the caller should Skip.
     */
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

                // 15-25% chance to overshoot on significant direction changes
                val angularSize = abs(rotationDiff.yaw) + abs(rotationDiff.pitch)
                if (angularSize > 5f && NoiseProvider.nextUniform(0f, 1f) < 0.20f) {
                    state.overshootTarget = computeOvershootTarget(
                        player.rotation, targetPoint
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
                state.overshootPhase = AimStrategy.State.OvershootPhase.IDLE
                state.overshootTarget = null
                result
            }
        }
    }

    // ---- Helpers ----

    /**
     * Compute an overshoot target by offsetting beyond the real target
     * by 5-15% of the rotation delta.
     */
    private fun computeOvershootTarget(currentRotation: Vec2, realTarget: Vec2): Vec2 {
        val delta = RotationCalculator.calculateDifference(currentRotation, realTarget)
        val overshootPercent = 0.05f + NoiseProvider.nextUniform(0f, 1f) * 0.10f
        return Vec2(
            realTarget.yaw + delta.yaw * overshootPercent,
            realTarget.pitch + delta.pitch * overshootPercent
        )
    }

    /**
     * Sample a reaction delay in ticks using a log-normal distribution.
     * Models human reaction time: median ~3 ticks at 20 TPS.
     */
    private fun sampleReactionDelay(): Int {
        val z = NoiseProvider.next(0f, 1f).toDouble()
        val delayTicks = exp(1.1 + 0.35 * z)
        return delayTicks.toInt().coerceIn(1, 6)
    }

    /**
     * Reset only overshoot state (not the full [AimStrategy.State]).
     */
    private fun AimStrategy.State.resetOvershoot() {
        overshootPhase = AimStrategy.State.OvershootPhase.IDLE
        overshootTarget = null
        overshootTicksRemaining = 0
    }
}
