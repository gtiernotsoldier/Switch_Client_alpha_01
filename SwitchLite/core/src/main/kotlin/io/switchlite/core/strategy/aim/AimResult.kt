package io.switchlite.core.strategy.aim

import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3

/**
 * Typed result from an [AimStrategy] execution.
 *
 * The adapter maps each variant to the appropriate action:
 *
 * - [ApplyRotation] → stores the target world point; the MAIN thread recomputes the rotation
 *   toward it every frame and applies it.
 * - [Skip]           → do nothing this tick.
 */
sealed class AimResult {

    /**
     * The strategy computed a TARGET WORLD POINT + per-axis interpolation fractions.
     *
     * The adapter stores the target point and fractions; the platform's MAIN render thread
     * recomputes the rotation from the player's current position every FRAME (frame-rate
     * smoothing, much smoother than the 20Hz tick).
     *
     * @property worldPoint the target point in world space to aim toward.
     * @property centerWorld the target hitbox CENTER in world space — used by the main thread for
     *        the per-frame MinFov "inside the box = aim freely" check.
     * @property minFov freedom-zone angle (degrees): while the crosshair is within minFov/2 of the
     *        center, the main thread lets the player aim freely (no pull).
     * @property fractionY yaw fraction of the remaining gap closed per render frame (0..1).
     * @property fractionP pitch fraction of the remaining gap closed per render frame (0..1).
     */
    data class ApplyRotation(
        val worldPoint: Vec3,
        val centerWorld: Vec3,
        val minFov: Float = 0f,
        val fractionY: Float = 0.2f,
        val fractionP: Float = 0.1f
    ) : AimResult()

    /**
     * The strategy decided not to adjust the aim this tick.
     * Reasons include: no target, out of range, condition fail,
     * reaction delay pending, already inside hitbox (LEGIT mode).
     */
    object Skip : AimResult()
}
