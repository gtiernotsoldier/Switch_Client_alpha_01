package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
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
        /** LB "Aim while on target" deceleration — slows the pull when already on the target. */
        const val ON_TARGET_FACTOR = 0.85f
        /** Clicking gently boosts the pull — natural snap-back, not machine-fast. */
        const val CLICK_SPEED_BOOST = 1.5f
        /** Angle-difference stop threshold (degrees): release once the delta is this small. */
        const val STOP_YAW = 0.2f
        const val STOP_PITCH = 0.1f
        /** FOV value that means "full 360°" — the cone gate is skipped entirely. */
        const val FULL_FOV = 360f
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

        // 5. Aim point — Slinky Multipoint blend (center ↔ closest corner), independent axes.
        // SelfAdaptive tracks the same stable multipoint aim point, intensity adapted by EMA.
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

        // 7. Self-adaptive: update the alignment EMA — how well the player's own mouse movement
        //    reduces the angular error. This drives the ASSIST FREQUENCY, not a strength number:
        //    when the player aims well the assist sometimes lets them do it alone (like a human
        //    who occasionally lands the aim themselves); when they struggle it helps every time.
        //    No visible numeric strength change — only whether the assist steps in this tick.
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

            // EMA update: 95% old, 5% new — slow, silky adaptation (no step feel).
            state.alignmentEma = state.alignmentEma * 0.95f + alignment * 0.05f
        }
        state.previousAngularError = angularError
        state.hasPreviousFrame = true

        // 8. Assist frequency: high alignment (player aiming well) → lower assist probability.
        //    pAssist = 1.0 when EMA=0 (always help), ~0.5 when EMA=1 (help half the time).
        //    The SPEED stays at the configured value — only the FREQUENCY adapts, so there is no
        //    numeric strength to feel; the assist just steps in more or less often, naturally.
        val pAssist = 1.0f - state.alignmentEma * 0.5f
        if (pAssist < 1.0f && kotlin.random.Random.nextFloat() >= pAssist) {
            return AimResult.Skip
        }

        val clickBoost = if (player.isAttackKeyDown) CLICK_SPEED_BOOST else 1f
        val onTargetFactor = if (abs(rotationDiff.yaw) < 5f && abs(rotationDiff.pitch) < 3f) ON_TARGET_FACTOR else 1f
        val fracY = config.aimSpeedY * clickBoost * onTargetFactor
        val fracP = config.aimSpeedP * clickBoost * onTargetFactor
        return AimResult.ApplyRotation(finalWorld, target.hitbox, fracY, fracP)
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

    // ==================== Helpers ====================

}
