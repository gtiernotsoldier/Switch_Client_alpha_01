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
     * The strategy computed a rotation that should be applied.
     *
     * @property rotation the (yaw, pitch) to set on the player camera.
     */
    data class ApplyRotation(
        val rotation: Vec2
    ) : AimResult()

    /**
     * The strategy decided not to adjust the aim this tick.
     * Reasons include: no target, out of range, condition fail,
     * reaction delay pending, already inside hitbox (LEGIT mode).
     */
    object Skip : AimResult()
}
