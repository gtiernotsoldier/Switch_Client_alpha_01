package io.doppel.core.strategy.aim

import io.doppel.core.model.Hitbox
import io.doppel.core.util.Vec3

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
     * @property hitbox the target hitbox — the main thread casts the player's current aim ray at
     *        it every frame: if the crosshair is on the box, the player aims freely (no pull).
     * @property fractionY yaw fraction of the remaining gap closed per render frame (0..1).
     * @property fractionP pitch fraction of the remaining gap closed per render frame (0..1).
     */
    data class ApplyRotation(
        val worldPoint: Vec3,
        val hitbox: Hitbox?,
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
