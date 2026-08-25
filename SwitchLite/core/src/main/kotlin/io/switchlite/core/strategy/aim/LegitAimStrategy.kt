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
 * LEGIT / NORMAL aim strategy, modeled on Raven-XD (LiquidBounce) NormalAimAssist.
 *
 * Per tick:
 * 1. Null-target guard → Skip.
 * 2. 3D range check → Skip.
 * 3. Unified condition check → Skip.
 * 4. Mode geometry:
 *    - NORMAL: lock onto the exact point the crosshair ray hits the box and track it as the
 *      target moves (continuous tracking). If the crosshair is off the box, pull it back to the
 *      nearest surface so it re-enters the box.
 *    - LEGIT: compute the box's yaw/pitch angular range. While the crosshair is inside that range
 *      (on the target) do nothing; only when it drifts outside, pull back to the nearest edge.
 * 5. FOV: 360° = full 360 (skip the cone gate); otherwise the target point must be inside the cone.
 * 6. Angle-difference proportional smoothing (fraction of the remaining delta per tick) with a
 *    click boost and an on-target stop threshold — fast, convergent, no edge jitter.
 */
class LegitAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
        /** LockOnCrosshair alignment threshold (degrees): crosshair must be this close to assist. */
        const val LOCK_ANGLE = 8f
        /** LB "Aim while on target" deceleration — slows the pull when already on the target. */
        const val ON_TARGET_FACTOR = 0.85f
        /** Clicking boosts the pull speed so a single click quickly snaps back into the box. */
        const val CLICK_SPEED_BOOST = 3f
        /** Angle-difference stop threshold (degrees): release once the delta is this small. */
        const val STOP_YAW = 0.2f
        const val STOP_PITCH = 0.1f
        /** FOV value that means "full 360°" — the cone gate is skipped entirely. */
        const val FULL_FOV = 360f
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

        // 4. Mode geometry — decide the target yaw/pitch this tick.
        val targetYaw: Float
        val targetPitch: Float
        val onTarget: Boolean
        when (config.mode) {
            AimMode.NORMAL -> {
                // Continuous tracking: aim at the point the crosshair ray hits the box and keep
                // tracking it as the target moves (crosshair-point lock, NOT the center). If the
                // ray misses (crosshair slightly off), pull back to the nearest surface so it
                // re-enters the box, then it tracks the hit point again.
                val hit = RotationCalculator.rayHitPoint(eyePos, aim, target.hitbox)
                if (hit != null) {
                    val rot = RotationCalculator.calculateRotation(eyePos, hit)
                    targetYaw = rot.yaw
                    targetPitch = rot.pitch
                    onTarget = true
                } else {
                    val edge = RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)
                    if (edge == null) return AimResult.Skip
                    targetYaw = edge.rotation.yaw
                    targetPitch = edge.rotation.pitch
                    onTarget = false
                }
            }
            AimMode.LEGIT -> {
                // Angular range of the box as seen from the eyes. Inside = on target → no pull
                // (legit never tracks while inside the box — it only corrects when you drift out).
                // Outside = pull back to the nearest edge.
                val range = RotationCalculator.getBoxAngleRange(eyePos, target.hitbox)
                if (range.isDegenerate) return AimResult.Skip
                val yaw = RotationCalculator.normalizeAngle(aim.yaw)
                val pitch = aim.pitch
                val inYaw = yaw in range.yawMin..range.yawMax
                val inPitch = pitch in range.pitchMin..range.pitchMax
                if (inYaw && inPitch) return AimResult.Skip
                onTarget = false
                targetYaw = if (yaw < range.yawMin) range.yawMin else if (yaw > range.yawMax) range.yawMax else aim.yaw
                targetPitch = if (pitch < range.pitchMin) range.pitchMin else if (pitch > range.pitchMax) range.pitchMax else aim.pitch
            }
            AimMode.SELF_ADAPTIVE -> {
                // Same crosshair-point tracking as NORMAL, but with adaptive intensity (EMA).
                val hit = RotationCalculator.rayHitPoint(eyePos, aim, target.hitbox)
                if (hit != null) {
                    val rot = RotationCalculator.calculateRotation(eyePos, hit)
                    targetYaw = rot.yaw
                    targetPitch = rot.pitch
                    onTarget = true
                } else {
                    val edge = RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)
                    if (edge == null) return AimResult.Skip
                    targetYaw = edge.rotation.yaw
                    targetPitch = edge.rotation.pitch
                    onTarget = false
                }
            }
        }

        // 5. FOV gate — full 360 means skip; otherwise the target point must be inside the cone.
        if (config.fov < FULL_FOV) {
            val diff = RotationCalculator.calculateDifference(aim, Vec2(targetYaw, targetPitch))
            if (abs(diff.yaw) > config.fov / 2f || abs(diff.pitch) > config.fov / 2f) {
                return AimResult.Skip
            }
        }

        val rotationDiff = RotationCalculator.calculateDifference(aim, Vec2(targetYaw, targetPitch))

        // 6. LockOnCrosshair: only assist once the crosshair is already aligned to the target.
        if (config.lockOnCrosshair) {
            if (abs(rotationDiff.yaw) > LOCK_ANGLE || abs(rotationDiff.pitch) > LOCK_ANGLE) {
                return AimResult.Skip
            }
        }

        // 7. Angle-difference proportional smoothing: move a fraction of the remaining yaw/pitch
        // delta each tick. Big gap → big move, small gap → small move — the pull converges
        // smoothly and never jitters at the edge (unlike fixed per-tick degree steps).
        // Clicking (isAttackKeyDown) boosts the fraction so one click snaps back into the box.
        val clickBoost = if (player.isAttackKeyDown) CLICK_SPEED_BOOST else 1f
        val fraction = (config.aimSpeed / 20f) * clickBoost * (if (onTarget) ON_TARGET_FACTOR else 1f)
        val f = fraction.coerceIn(0f, 1f)
        // Stop threshold: once the delta is tiny, release entirely (no micro-jitter at the edge).
        if (abs(rotationDiff.yaw) < STOP_YAW && abs(rotationDiff.pitch) < STOP_PITCH) {
            return AimResult.Skip
        }
        val newYaw = aim.yaw + rotationDiff.yaw * f
        val newPitch = aim.pitch + rotationDiff.pitch * f * 0.39f
        return AimResult.ApplyRotation(Vec2(newYaw, newPitch))
    }
}
