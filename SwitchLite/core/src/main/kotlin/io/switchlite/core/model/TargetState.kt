package io.switchlite.core.model

/**
 * Snapshot of target entity state at a specific tick.
 * Pure data class, no Minecraft dependencies.
 */
data class TargetState(
    val entityId: Int,
    val name: String,
    val posX: Double,
    val posY: Double,
    val posZ: Double,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val health: Float,
    val hurtTime: Int,
    val distance: Float,
    val hitbox: Hitbox
)

/**
 * Axis-aligned bounding box representation.
 */
data class Hitbox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double
)
