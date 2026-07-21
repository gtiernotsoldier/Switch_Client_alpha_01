package io.switchlite.core.combat.model

import io.switchlite.core.util.Vec3

/**
 * Combat context containing all relevant battle state
 * Pure data class with no Minecraft dependencies
 */
data class CombatContext(
    val player: PlayerState,
    val target: TargetState?,
    val nearbyEntities: List<TargetState>,
    val ping: Int,
    val fps: Int,
    val serverTPS: Float
)
