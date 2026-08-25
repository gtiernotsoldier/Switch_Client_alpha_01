package io.switchlite.core.strategy.aim

import io.switchlite.core.util.Vec2

/**
 * Typed result from an [AimStrategy] execution.
 *
 * The adapter maps each variant to the appropriate action:
 *
 * - [ApplyRotation] → `EventBridge.setPlayerRotation(rotation)`.
 * - [Skip]           → do nothing this tick.
 */
sealed class AimResult {

    /**
     * The strategy computed the TARGET rotation + per-axis interpolation fractions.
     *
     * The adapter stores the target and fractions; the platform's MAIN render thread interpolates
     * the player's rotation toward the target every FRAME (frame-rate smoothing, much smoother
     * than the 20Hz tick).
     *
     * @property rotation the target (yaw, pitch) to aim toward.
     * @property fractionY yaw fraction of the remaining gap closed per render frame (0..1).
     * @property fractionP pitch fraction of the remaining gap closed per render frame (0..1).
     */
    data class ApplyRotation(
        val rotation: Vec2,
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
