package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import kotlin.math.abs

/**
 * SelfAdaptive aim strategy.
 *
 * Kept as a separate mode so the player keeps their SelfAdaptive preset, but it behaves exactly
 * like [LegitAimStrategy]'s fixed silky assist — no numeric strength, no probability, no
 * measurement (measurements of mouse skill are inaccurate and felt as "hard"). The smoothness
 * comes from the main-thread frame interpolation + soft landing, which both modes share.
 */
class SelfAdaptiveAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
        /** LockOnCrosshair alignment threshold (degrees): crosshair must be this close to assist. */
        const val LOCK_ANGLE = 8f
        /** LB "Aim while on target" deceleration — slows the pull when already on the target. */
        const val ON_TARGET_FACTOR = 0.85f
        /** Clicking gently boosts the pull — natural snap-back, not machine-fast. */
        const val CLICK_SPEED_BOOST = 1.5f
        /** FOV value that means "full 360°" — the cone gate is skipped entirely. */
        const val FULL_FOV = 360f
    }

    /** State — just the shared aim state (no numeric adaptation). */
    class AdaptiveState : AimStrategy.State()

    override fun execute(
        config: AimConfig,
        state: AimStrategy.State,
        input: Any
    ): AimResult {
        require(input is AimInput) { "SelfAdaptiveAimStrategy expects AimInput" }
        val adaptiveState = state as? AdaptiveState
            ?: return AimResult.Skip // Wrong state type — should not happen
        return processTick(config, adaptiveState, input.player, input.target)
    }

    // ---- Visible for testing ----

    internal fun processTick(
        config: AimConfig,
        state: AdaptiveState,
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

        // 2. 3D range check (full sphere distance)
        val distance3D = player.position.distanceTo(target.position)
        if (distance3D < config.rangeMin || distance3D > config.rangeMax) {
            return AimResult.Skip
        }

        // 3. Condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return AimResult.Skip
        }

        // 4. Target switch detection
        if (target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
        }

        // 5. Aim point — Slinky Multipoint blend (center ↔ closest corner), independent axes.
        val aimPoint = RotationCalculator.multipointAimPoint(eyePos, target.hitbox, config.multipointX, config.multipointY)
        val targetRot = RotationCalculator.calculateRotation(eyePos, aimPoint)

        // NOTE: the "crosshair on the box = aim freely" check runs on the MAIN thread every frame
        // (raycast from the player's current aim at the hitbox). We only pass the hitbox here.

        // 6. FOV gate — 360 = full (skip); otherwise the aim point must be inside the cone.
        if (config.fov < FULL_FOV) {
            val diff = RotationCalculator.calculateDifference(aim, targetRot)
            if (abs(diff.yaw) > config.fov / 2f || abs(diff.pitch) > config.fov / 2f) {
                return AimResult.Skip
            }
        }

        // 6c. Natural drift — the aim point wanders slowly within ±offset, human-like. Applied as
        // a small world-space lateral offset around the multipoint point.
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

        val rotationDiff = RotationCalculator.calculateDifference(aim, targetRot)

        // LockOnCrosshair: only assist once the crosshair is already aligned to the target.
        if (config.lockOnCrosshair) {
            if (abs(rotationDiff.yaw) > LOCK_ANGLE || abs(rotationDiff.pitch) > LOCK_ANGLE) {
                return AimResult.Skip
            }
        }

        // 7. No measurement, no numeric strength, no probability: SelfAdaptive behaves exactly
        //    like Normal — fixed silky speed (SpeedY/SpeedP) + main-thread soft landing. There is
        //    nothing numeric to feel and nothing inaccurate to measure; the mode exists so the
        //    player can keep their SelfAdaptive preset while getting the same smooth assist.
        val clickBoost = if (player.isAttackKeyDown) CLICK_SPEED_BOOST else 1f
        val onTargetFactor = if (abs(rotationDiff.yaw) < 5f && abs(rotationDiff.pitch) < 3f) ON_TARGET_FACTOR else 1f
        val fracY = config.aimSpeedY * clickBoost * onTargetFactor
        val fracP = config.aimSpeedP * clickBoost * onTargetFactor
        return AimResult.ApplyRotation(finalWorld, target.hitbox, fracY, fracP)
    }
}


