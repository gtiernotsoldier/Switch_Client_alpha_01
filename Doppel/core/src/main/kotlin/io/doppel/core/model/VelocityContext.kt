package io.doppel.core.model

import io.doppel.core.util.Vec3

/**
 * VelocityContext: Bundles all data required for velocity processing.
 * Passed from Adapter to Module logic, ensuring zero game object leakage.
 */
data class VelocityContext(
    val originalMotion: Vec3,
    val player: PlayerState,
    val target: TargetState?,
    val packetHandle: Any,      // Opaque handle for Delay mode cancellation
    /**
     * True when the knockback came from being hit (S12 velocity packet) vs an explosion (S27).
     * Lets the Velocity module's OnlyOnHitFrame mode reduce ONLY on attack knockback and pass
     * explosions through — the "hit frame" signal that doesn't depend on cross-thread field reads.
     */
    val isKnockbackHit: Boolean = true
)
