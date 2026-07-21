package io.switchlite.core.combat.model

/**
 * Snapshot of target state for strategy calculations.
 * Contains only essential data, no Minecraft dependencies.
 */
data class TargetState(
    val entityId: Int,
    val posX: Double,
    val posY: Double,
    val posZ: Double,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val yaw: Float,
    val pitch: Float,
    val health: Float,
    val distanceToPlayer: Double,
    val isMovingBackward: Boolean,
    val hitBoxWidth: Float,
    val hitBoxHeight: Float
)
