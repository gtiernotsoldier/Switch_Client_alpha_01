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
 *    - NORMAL / SELF_ADAPTIVE: continuous tracking — aim at the hitbox center so the crosshair
 *      follows the target wherever it moves (not a fixed point).
 *    - LEGIT: no tracking inside the box; only pull the crosshair back to the nearest box surface
 *      when it drifts out, and STOP there (edge stop, no hard lock).
 * 5. Angular FOV cone check → Skip if outside.
 * 6. LockOnCrosshair gate → Skip if not already aligned (when enabled).
 * 7. Nemui-style proportional smoothing + stopping gate → emit [AimResult.ApplyRotation].
 */
class LegitAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
        /** LockOnCrosshair alignment threshold (degrees): crosshair must be this close to assist. */
        const val LOCK_ANGLE = 8f
        /** Nemui smoothStopping: stop applying micro-corrections once this close (prevents jitter). */
        const val STOP_YAW = 0.2f
        const val STOP_PITCH = 0.1f
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
            AimMode.NORMAL, AimMode.SELF_ADAPTIVE -> {
                // Continuous tracking: aim at the hitbox center every tick. The crosshair stays on
                // the target as it moves (and when the crosshair passes through it, it keeps
                // tracking) — not locked to a single fixed point.
                RotationCalculator.hitboxCenterWorld(target.hitbox)
            }
            AimMode.LEGIT -> {
                // No tracking inside the box. Only pull back to the nearest box surface when the
                // crosshair drifts outside, and stop at the edge (no hard lock to a corner).
                val edge = RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)
                if (edge == null) return AimResult.Skip
                edge.world
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

        // 7. Nemui-style proportional smoothing + stopping gate.
        // Close a fraction of the remaining yaw/pitch gap each tick (exponential ease), so the
        // pull is fast when far and tapers off when close — no fixed-degree cap, no overshoot.
        // aimSpeed=20 → ~50% of the gap per tick; aimSpeed=8 → ~20%.
        val yawFraction = 0.5f * (config.aimSpeed / 20f) * config.smoothness
        val pitchFraction = yawFraction * 0.39f
        val smoothed = Vec2(
            aim.yaw + rotationDiff.yaw * yawFraction,
            aim.pitch + rotationDiff.pitch * pitchFraction
        )
        // Nemui smoothStopping: once already this close to the aim point, stop correcting —
        // otherwise the exponential glide keeps micro-moving near the target (feels stiff/jittery).
        if (abs(rotationDiff.yaw) < STOP_YAW && abs(rotationDiff.pitch) < STOP_PITCH) {
            return AimResult.Skip
        }
        return AimResult.ApplyRotation(smoothed)
    }
}
