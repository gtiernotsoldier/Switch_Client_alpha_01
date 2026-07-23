package io.switchlite.core.strategy.velocity

import io.switchlite.core.util.Vec3

/**
 * Typed result from a [VelocityStrategy] execution.
 *
 * The adapter layer maps each variant to the appropriate
 * [io.switchlite.core.model.PlatformCommand]:
 *
 * - [Modify]  → `PlatformCommand.ModifyMotion`
 * - [Cancel]  → `PlatformCommand.CancelPacket`
 * - [ClickBurst] → `PlatformCommand.ClickBurst`
 * - [Pass]    → `PlatformCommand.Pass`
 * - [NoOp]    → `PlatformCommand.NoOp`
 */
sealed class VelocityResult {

    /**
     * Scale the original velocity by [horizontalFactor] and [verticalFactor].
     * The adapter applies these to the X/Z and Y components respectively.
     */
    data class Modify(
        val motion: Vec3
    ) : VelocityResult()

    /**
     * Cancel the incoming packet entirely (used by DELAY mode to buffer).
     * The adapter must also store the packet handle for later release.
     *
     * @param packetHandle the opaque handle from [io.switchlite.core.model.VelocityContext.packetHandle].
     */
    data class Cancel(
        val packetHandle: Any
    ) : VelocityResult()

    /**
     * Issue a burst of clicks on the target entity.
     * The adapter maps this to `PlatformCommand.ClickBurst`.
     */
    data class ClickBurst(
        val targetEntityId: Int,
        val times: Int
    ) : VelocityResult()

    /**
     * Pass the original velocity through unmodified.
     */
    data class Pass(
        val originalMotion: Vec3
    ) : VelocityResult()

    /**
     * Do absolutely nothing (strategy decided to skip this tick).
     */
    object NoOp : VelocityResult()
}
