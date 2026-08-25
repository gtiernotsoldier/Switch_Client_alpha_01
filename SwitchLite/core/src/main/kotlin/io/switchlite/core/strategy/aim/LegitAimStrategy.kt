package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import kotlin.math.abs

/**
 * LEGIT / NORMAL / LINEAR aim strategy, rebuilt on Slinky's professional AimAssist design.
 *
 * Per tick:
 * 1. Null-target guard → Skip.
 * 2. 3D range check → Skip.
 * 3. Unified condition check → Skip.
 * 4. Aim point = Slinky Multipoint: hitbox center ↔ closest-corner linear blend, independent
 *    horizontal/vertical factors (stable, no corner snapping).
 * 5. MinFov freedom zone: while the crosshair is within `minFov` of the target center, aim freely
 *    (inside the hitbox) — assist only pulls when drifting outside. Hides the assist.
 * 6. FOV gate: 360 = full (skip); otherwise target must be inside the cone.
 * 7. LockOnCrosshair gate (optional).
 * 8. Speed: independent yaw/pitch fractions; Regular (exponential ease) or Linear (near-linear).
 *    Actual frame smoothing + player-yield run on the main render thread.
 */
class LegitAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
        /** LockOnCrosshair alignment threshold (degrees). */
        const val LOCK_ANGLE = 8f
        /** LB "Aim while on target" deceleration. */
        const val ON_TARGET_FACTOR = 0.85f
        /** Clicking gently boosts the pull. */
        const val CLICK_SPEED_BOOST = 1.5f
        /** FOV value that means "full 360°" — the cone gate is skipped entirely. */
        const val FULL_FOV = 360f
    }

    override fun execute(
        config: AimConfig,
        state: AimStrategy.State,
        input: Any
    ): AimResult {
        require(input is AimInput) { "LegitAimStrategy expects AimInput" }
        return processTick(config, state, input.player, input.target, input.mouseDeltaX, input.mouseDeltaY)
    }

    // ---- Visible for testing ----

    internal fun processTick(
        config: AimConfig,
        state: AimStrategy.State,
        player: PlayerState,
        target: TargetState?,
        mouseDeltaX: Float = 0f,
        mouseDeltaY: Float = 0f
    ): AimResult {
        // 1. Null guard
        if (target == null) {
            state.reset()
            return AimResult.Skip
        }

        val eyePos = Vec3(player.position.x, player.position.y + EYE_HEIGHT, player.position.z)
        val aim = player.rotation

        // 2. 3D range check (full sphere distance)
        val distance3D = player.position.distanceTo(target.position)
        if (distance3D < config.rangeMin || distance3D > config.rangeMax) {
            return AimResult.Skip
        }

        // 3. Condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return AimResult.Skip
        }

        // 4. Aim point — Slinky Multipoint blend (center ↔ closest corner), independent axes.
        // All modes share this stable aim point; the mode only adjusts behavior around it.
        val aimPoint = RotationCalculator.multipointAimPoint(eyePos, target.hitbox, config.multipointX, config.multipointY)
        val centerRot = RotationCalculator.calculateRotation(eyePos, RotationCalculator.hitboxCenterWorld(target.hitbox))
        val targetRot = RotationCalculator.calculateRotation(eyePos, aimPoint)

        // 5. MinFov freedom zone: crosshair within `minFov` of the target CENTER = inside the
        // hitbox → aim freely (no pull). This is the "hidden assist" behavior: the player owns
        // the box; the assist only engages when the crosshair drifts out.
        if (config.minFov > 0f) {
            val toCenter = RotationCalculator.calculateDifference(aim, centerRot)
            if (abs(toCenter.yaw) <= config.minFov / 2f && abs(toCenter.pitch) <= config.minFov / 2f) {
                return AimResult.Skip
            }
        }

        // 6. FOV gate — 360 = full (skip); otherwise the aim point must be inside the cone.
        if (config.fov < FULL_FOV) {
            val diff = RotationCalculator.calculateDifference(aim, targetRot)
            if (abs(diff.yaw) > config.fov / 2f || abs(diff.pitch) > config.fov / 2f) {
                return AimResult.Skip
            }
        }

        // 6b. Natural drift — the aim point wanders slowly (human-like hand noise). Applied as a
        // small world-space offset around the multipoint point.
        val (driftYaw, driftPitch) = RotationCalculator.updateNaturalDrift(state, config.offset)
        val eyeToTarget = Vec3(
            aimPoint.x - eyePos.x,
            aimPoint.y - eyePos.y,
            aimPoint.z - eyePos.z
        )
        val dist = eyeToTarget.length()
        val latX = -eyeToTarget.z / (if (dist > 0.001) dist else 1.0)
        val latZ = eyeToTarget.x / (if (dist > 0.001) dist else 1.0)
        val finalWorld = Vec3(
            aimPoint.x + latX * driftYaw * 0.02 * dist,
            aimPoint.y + driftPitch * 0.02 * dist,
            aimPoint.z + latZ * driftYaw * 0.02 * dist
        )

        // 7. LockOnCrosshair gate (optional): require the crosshair to already be near the aim.
        if (config.lockOnCrosshair) {
            val diff = RotationCalculator.calculateDifference(aim, targetRot)
            if (abs(diff.yaw) > LOCK_ANGLE || abs(diff.pitch) > LOCK_ANGLE) {
                return AimResult.Skip
            }
        }

        // 8. Speed — independent yaw/pitch fractions per frame.
        // Regular: exponential ease (fraction of remaining gap). Linear: near-linear (fixed small
        // fraction, stable low-speed tracking — Slinky Linear mode).
        val clickBoost = if (player.isAttackKeyDown) CLICK_SPEED_BOOST else 1f
        val rotDiff = RotationCalculator.calculateDifference(aim, targetRot)
        val onTargetFactor = if (abs(rotDiff.yaw) < 5f && abs(rotDiff.pitch) < 3f) ON_TARGET_FACTOR else 1f
        val fracY = if (config.linear) {
            0.06f * clickBoost * onTargetFactor * config.smoothness
        } else {
            config.aimSpeedY * clickBoost * onTargetFactor * config.smoothness
        }
        val fracP = if (config.linear) {
            0.06f * clickBoost * onTargetFactor * config.smoothness
        } else {
            config.aimSpeedP * clickBoost * onTargetFactor * config.smoothness
        }
        return AimResult.ApplyRotation(finalWorld, fracY, fracP)
    }
}
