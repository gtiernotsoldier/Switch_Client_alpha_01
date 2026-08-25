package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import kotlin.math.abs

/**
 * Default LEGIT / NORMAL aim strategy.
 *
 * Processing pipeline per tick:
 * 1. Null-target guard → Skip.
 * 2. 3D range check (full sphere distance) → Skip.
 * 3. Unified condition check → Skip.
 * 4. Target point computation:
 *    - LEGIT: pull the crosshair back to the nearest box surface if it drifted out; Skip if inside.
 *    - NORMAL: lock onto the exact point the crosshair ray hits; pull toward it until it hits.
 * 5. Angular FOV cone check → Skip if outside.
 * 6. LockOnCrosshair gate → Skip if not already aligned (when enabled).
 * 7. Nemui-style proportional smoothing → emit [AimResult.ApplyRotation].
 */
class LegitAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
        /** LockOnCrosshair alignment threshold (degrees): crosshair must be this close to assist. */
        const val LOCK_ANGLE = 8f
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
            return AimResult.Skip
        }

        // 3. Condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return AimResult.Skip
        }

        // 4. Target point computation (world point + rotation)
        val targetPoint: Vec3 = when (config.mode) {
            AimMode.SELF_ADAPTIVE -> {
                // Nemui-style pull: aim at the entity center (strongest pull-back).
                RotationCalculator.hitboxCenterWorld(target.hitbox)
            }
            AimMode.LEGIT -> {
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

        // 5. Angular FOV cone check — angle measured from the player's view line (0-360°).
        if (!RotationCalculator.isWithinFov3D(eyePos, aim, targetPoint, config.fov)) {
            return AimResult.Skip
        }

        val rotationDiff = RotationCalculator.calculateDifference(aim, targetRotation)

        // 6. LockOnCrosshair: only assist once the crosshair is already aligned to the target.
        // Off = assist anywhere inside the FOV cone.
        if (config.lockOnCrosshair) {
            if (abs(rotationDiff.yaw) > LOCK_ANGLE || abs(rotationDiff.pitch) > LOCK_ANGLE) {
                return AimResult.Skip
            }
        }

        // 7. Nemui-style proportional smoothing: close a fraction of the remaining yaw/pitch gap
        // each tick, easing toward the aim point. Mirrors Nemui's SimpleAnimation
        // (fraction = 0.35 / (10 / speed), speed = aimSpeed / 20). Pitch closes slower than yaw.
        // aimSpeed=20 → fraction ≈ 0.35 (Nemui max speed, ~35% of the gap per tick); aimSpeed=8 → ~0.14.
        val yawFraction = 0.35f * (config.aimSpeed / 20f) * config.smoothness
        val pitchFraction = yawFraction * 0.39f
        val smoothed = Vec2(
            aim.yaw + rotationDiff.yaw * yawFraction,
            aim.pitch + rotationDiff.pitch * pitchFraction
        )
        return AimResult.ApplyRotation(smoothed)
    }
}
