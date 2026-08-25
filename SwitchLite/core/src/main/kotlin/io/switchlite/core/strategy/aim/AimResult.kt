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
     * The strategy computed the TARGET rotation + a per-frame interpolation fraction.
     *
     * The adapter stores the target and fraction; the platform's MAIN render thread interpolates
     * the player's rotation toward the target every FRAME (frame-rate smoothing, much smoother
     * than the 20Hz tick).
     *
     * @property rotation the target (yaw, pitch) to aim toward.
     * @property fraction fraction of the remaining gap closed per render frame (0..1). Includes
     *        the player-turn yield (fast mouse = assist yields), click boost, and on-target
     *        deceleration — computed by the strategy each background tick.
     */
    data class ApplyRotation(
        val rotation: Vec2,
        val fraction: Float = 0.2f
    ) : AimResult()

    /**
     * The strategy decided not to adjust the aim this tick.
     * Reasons include: no target, out of range, condition fail,
     * reaction delay pending, already inside hitbox (LEGIT mode).
     */
    object Skip : AimResult()
}
