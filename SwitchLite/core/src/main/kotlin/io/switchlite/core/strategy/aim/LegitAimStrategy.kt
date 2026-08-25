package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.Vec3
import kotlin.math.exp

/**
 * Default LEGIT / NORMAL aim strategy with full humanization.
 *
 * Processing pipeline per tick:
 * 1. Null-target guard → Skip.
 * 2. 3D range check (full sphere distance) → Skip.
 * 3. Unified condition check → Skip.
 * 4. Target-switch detection → reset overshoot, sample reaction delay.
 * 5. Reaction delay countdown → Skip while > 0.
 * 6. Target point computation:
 *    - LEGIT: pull the crosshair back to the nearest box surface if it drifted out; Skip if inside.
 *    - NORMAL: lock onto the exact point the crosshair ray hits; pull toward it until it hits.
 * 7. Spherical FOV check → Skip if outside the 3D cone.
 * 8. Smoothing & velocity-limited glide (separate yaw/pitch factors).
 * 9. Overshoot state machine (IDLE → OVERSHOOT → CORRECT → IDLE).
 * 10. Random-walk noise injection.
 * 11. Emit [AimResult.ApplyRotation].
 */
class LegitAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
    }

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

        val eyePos = Vec3(player.position.x, player.position.y + EYE_HEIGHT, player.position.z)
        val aim = player.rotation

        // 2. 3D range check (full sphere distance, not just X/Z)
        val distance3D = player.position.distanceTo(target.position)
        if (distance3D < config.rangeMin || distance3D > config.rangeMax) {
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

        // 6. Target point computation (world point + rotation)
        val targetPoint: Vec3 = when (config.mode) {
            AimMode.LEGIT, AimMode.SELF_ADAPTIVE -> {
                // Inside the collision box -> do nothing. Only correct the crosshair back to the
                // box edge when it drifts outside, and STOP there (no hard lock to a corner).
                val edge = RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)
                if (edge == null) return AimResult.Skip
                edge.world
            }
            AimMode.NORMAL -> {
                // Lock onto the exact point the crosshair ray hits the box. If the ray misses,
                // pull toward the nearest surface until it hits, then it locks there.
                val hit = RotationCalculator.rayHitPoint(eyePos, aim, target.hitbox)
                hit ?: (RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)?.world
                    ?: target.position)
            }
        }
        val targetRotation = RotationCalculator.calculateRotation(eyePos, targetPoint)

        // 7. Spherical FOV check (3D cone; radius grows with distance)
        if (!RotationCalculator.isWithinFov3D(eyePos, aim, targetPoint, config.fov)) {
            return AimResult.Skip
        }

        // 8. Smoothing factors = max degrees moved per tick (velocity-limited glide, not a
        // proportional blend). Default: 8 * 0.5 * 0.85 ≈ 3.4° yaw / tick, pitch ~60% of that.
        val yawFactor = config.aimSpeed * 0.5f * config.smoothness
        val pitchFactor = config.aimSpeed * 0.5f * config.smoothness * 0.6f

        val rotationDiff = RotationCalculator.calculateDifference(aim, targetRotation)

        // 9. Overshoot state machine (shared via OvershootHelper)
        val finalRotation = OvershootHelper.execute(
            state = state,
            player = player,
            targetPoint = targetRotation,
            rotationDiff = rotationDiff,
            yawFactor = yawFactor,
            pitchFactor = pitchFactor
        ) ?: return AimResult.Skip

        // 10. Noise injection
        val noisyRotation = NoiseProvider.applyWalk(finalRotation, config.noiseIntensity)

        return AimResult.ApplyRotation(noisyRotation)
    }

    // ---- Helpers ----

    /**
     * Sample a reaction delay in ticks using a log-normal distribution.
     * Models human reaction time: median ~3 ticks at 20 TPS.
     */
    private fun sampleReactionDelay(): Int {
        val z = NoiseProvider.next(0f, 1f).toDouble()
        val delayTicks = exp(1.1 + 0.35 * z)
        return delayTicks.toInt().coerceIn(1, 6)
    }
}
