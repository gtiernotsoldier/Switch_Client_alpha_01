package io.doppel.core.strategy.movement

import io.doppel.core.math.PortableMath


/**
 * The arithmetic behind Instant Stop: how long to keep braking after the player lets go of a key.
 *
 * The module's action is a short tap of the opposite key. What stops the tap is not a timer but the
 * player's own momentum — pressing the opposite key for a fixed number of ticks moves you backwards
 * once the forward component is gone, which is both slower and visibly wrong.
 *
 * All of it is one projection: take the direction the player was travelling, project the current
 * horizontal velocity onto it, and stop when that projection reaches zero.
 */
object StopTap {

    /** Below this horizontal speed there is nothing left to brake. Blocks/tick. */
    const val MIN_SPEED = 0.02

    /**
     * Whether the player is still sliding in the direction they just released.
     *
     * @param yawDegrees the player's yaw at this tick — the released direction is relative to where
     *   they are facing *now*, not to where they were facing when they let go.
     * @param releasedForward true when W was released (sliding forward), false for S.
     */
    fun stillSliding(
        motionX: Double,
        motionZ: Double,
        yawDegrees: Float,
        releasedForward: Boolean,
        minSpeed: Double = MIN_SPEED
    ): Boolean {
        val yaw = PortableMath.toRadians(yawDegrees.toDouble())
        val forwardX = -PortableMath.sin(yaw)
        val forwardZ = PortableMath.cos(yaw)
        val dirX = if (releasedForward) forwardX else -forwardX
        val dirZ = if (releasedForward) forwardZ else -forwardZ
        return motionX * dirX + motionZ * dirZ > minSpeed
    }
}
