package io.switchlite.core.combat.model

import io.switchlite.core.util.Vec3

/**
 * Target state snapshot (pure data, no game references)
 */
data class TargetState(
    val entityId: Int,
    val position: Vec3,
    val velocity: Vec3,
    val health: Float,
    val isLiving: Boolean,
    val hitboxYaw: ClosedFloatingPointRange<Float>,
    val hitboxPitch: ClosedFloatingPointRange<Float>,
    val distanceToPlayer: Double
)
