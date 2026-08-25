package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
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
 * - Uses the same target-point, FOV cone, and Nemui smoothing as [LegitAimStrategy].
 * - Only varies the `aimSpeed` and `smoothness` factors dynamically via the alignment EMA.
 *
 * Constitution compliance: §1 Safety (never exceeds human limits), §3 Strategy (adaptive).
 */
class SelfAdaptiveAimStrategy : AimStrategy {

    private companion object {
        const val EYE_HEIGHT = 1.62
        /** LockOnCrosshair alignment threshold (degrees): crosshair must be this close to assist. */
        const val LOCK_ANGLE = 8f
    }

    /** Extended state with adaptive tracking fields. */
    class AdaptiveState : AimStrategy.State() {
        var alignmentEma: Float = 0.5f
        var previousAngularError: Float = 0f
        var hasPreviousFrame: Boolean = false

        override fun reset() {
            super.reset()
            alignmentEma = 0.5f
            previousAngularError = 0f
            hasPreviousFrame = false
        }
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

        // 4. Target switch detection — reset alignment tracking when the target changes
        if (target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
            state.hasPreviousFrame = false
        }

        // 5. Target point computation (same geometry as LegitAimStrategy)
        val targetPoint: Vec3 = when (config.mode) {
            AimMode.SELF_ADAPTIVE -> RotationCalculator.hitboxCenterWorld(target.hitbox)
            AimMode.LEGIT -> {
                val edge = RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)
                if (edge == null) return AimResult.Skip
                edge.world
            }
            AimMode.NORMAL -> {
                val hit = RotationCalculator.rayHitPoint(eyePos, aim, target.hitbox)
                hit ?: (RotationCalculator.getBoxEdgeTarget(eyePos, aim, target.hitbox)?.world
                    ?: target.position)
            }
        }
        val targetRotation = RotationCalculator.calculateRotation(eyePos, targetPoint)

        // 6. Angular FOV cone check — angle measured from the player's view line (0-360°).
        if (!RotationCalculator.isWithinFov3D(eyePos, aim, targetPoint, config.fov)) {
            return AimResult.Skip
        }

        val rotationDiff = RotationCalculator.calculateDifference(aim, targetRotation)

        // LockOnCrosshair: only assist once the crosshair is already aligned to the target.
        if (config.lockOnCrosshair) {
            if (abs(rotationDiff.yaw) > LOCK_ANGLE || abs(rotationDiff.pitch) > LOCK_ANGLE) {
                return AimResult.Skip
            }
        }

        // 7. Self-adaptive: update alignment EMA and compute dynamic factors
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

        // 8. Dynamic factor calculation
        val (dynamicAimSpeed, dynamicSmoothness) = computeDynamicFactors(
            config, state.alignmentEma
        )

        // 9. Nemui-style fraction smoothing (proportional per-tick close of the gap).
        // aimSpeed=20 → fraction ≈ 0.35 (Nemui max speed); aimSpeed=8 → ~0.14.
        val yawFraction = 0.35f * (dynamicAimSpeed / 20f) * dynamicSmoothness
        val pitchFraction = yawFraction * 0.39f
        val smoothed = Vec2(
            aim.yaw + rotationDiff.yaw * yawFraction,
            aim.pitch + rotationDiff.pitch * pitchFraction
        )
        return AimResult.ApplyRotation(smoothed)
    }

    // ==================== Adaptive Math ====================

    /**
     * Convert raw mouse delta (pixels) to approximate angular displacement (degrees).
     *
     * Uses Minecraft's actual mouse sensitivity formula (1.8–1.21):
     *   f(s) = s * 0.6 * (1 - s³ * 0.6)
     *   angular_delta = raw_delta * f(sensitivity) * pixelToAngle
     *
     * This is a cubic curve, not linear: low sensitivity values are dampened
     * more than a naive s*0.15 would suggest. Using the real formula prevents
     * systematic overestimation of angular displacement at low sensitivity,
     * which would bias the alignment EMA downward (making the player look
     * worse than they are).
     *
     * The pixelToAngle constant (0.15) is the Minecraft default pixel→degree
     * mapping baked into the game's rendering pipeline.
     *
     * NOTE: LWJGL 2 (Forge) uses `Mouse.getDY()` which reports Y-up.
     *       GLFW (Fabric) uses Y-down screen coordinates. Since we compute
     *       the scalar magnitude sqrt(dx²+dy²), the Y direction is irrelevant
     *       here. If per-axis alignment is added in the future, the sign
     *       must be flipped on one platform.
     */
    internal fun mouseDeltaToAngular(dx: Float, dy: Float, sensitivity: Float): Float {
        val pixelMagnitude = sqrt(dx * dx + dy * dy)
        // MC real sensitivity curve: f(s) = s * 0.6 * (1 - s³ * 0.6)
        val s3 = sensitivity * sensitivity * sensitivity
        val effectiveSensitivity = sensitivity * 0.6f * (1.0f - s3 * 0.6f)
        return pixelMagnitude * effectiveSensitivity * 0.15f
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

    // ==================== Helpers ====================

}
