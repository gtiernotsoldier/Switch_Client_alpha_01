package io.switchlite.core.combat.model

import io.switchlite.core.util.Vec3

/**
 * Player state snapshot
 * Pure data class with no Minecraft dependencies
 */
data class PlayerState(
    val position: Vec3,
    val motion: Vec3,
    val rotationYaw: Float,
    val rotationPitch: Float,
    val onGround: Boolean,
    val isMoving: Boolean,
    val isMovingForward: Boolean,
    val isSprinting: Boolean,
    val health: Float,
    val ticksExisted: Int
)
