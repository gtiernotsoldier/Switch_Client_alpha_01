package io.switchlite.core.combat.model

/**
 * Snapshot of player state for strategy calculations.
 * Contains only essential data, no Minecraft dependencies.
 */
data class PlayerState(
    val posX: Double,
    val posY: Double,
    val posZ: Double,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val yaw: Float,
    val pitch: Float,
    val isOnGround: Boolean,
    val isMoving: Boolean,
    val isMovingForward: Boolean,
    val isSprinting: Boolean,
    val health: Float,
    val ticksExisted: Int
)
