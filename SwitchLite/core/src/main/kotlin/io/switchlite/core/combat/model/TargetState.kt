package io.switchlite.core.combat.model

import io.switchlite.core.util.Vec3

/**
 * Target state snapshot
 * Pure data class with no Minecraft dependencies
 */
data class TargetState(
    val entityId: Int,
    val position: Vec3,
    val motion: Vec3,
    val rotationYaw: Float,
    val rotationPitch: Float,
    val health: Float,
    val distance: Float,
    val isMovingTowardsPlayer: Boolean,
    val isGoingBack: Boolean
)
