package io.switchlite.core.model

import io.switchlite.core.util.Vec3

/**
 * VelocityContext: Bundles all data required for velocity processing.
 * Passed from Adapter to Module logic, ensuring zero game object leakage.
 */
data class VelocityContext(
    val originalMotion: Vec3,
    val player: PlayerState,
    val target: TargetState?,
    val packetHandle: Any      // Opaque handle for Delay mode cancellation
)
