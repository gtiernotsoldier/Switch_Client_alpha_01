package io.switchlite.core.model

import io.switchlite.core.util.Vec3

/**
 * Snapshot of target entity state at a specific tick.
 * Pure data class, no Minecraft dependencies.
 */
data class TargetState(
    val entityId: Int,
    val name: String,
    val position: Vec3,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val health: Float,
    val hurtTime: Int,               // NEW: Target's current hurt time
    val isMovingBackward: Boolean,   // NEW: Whether target is moving backward (for onlyWhenTargetGoesBack)
    val distance: Float,
    val hitbox: Hitbox
) {
    // Legacy constructor for backward compatibility
    constructor(
        entityId: Int, name: String,
        posX: Double, posY: Double, posZ: Double,
        motionX: Double, motionY: Double, motionZ: Double,
        health: Float, hurtTime: Int, distance: Float, hitbox: Hitbox
    ) : this(
        entityId = entityId,
        name = name,
        position = Vec3(posX, posY, posZ),
        motionX = motionX, motionY = motionY, motionZ = motionZ,
        health = health,
        hurtTime = hurtTime,
        isMovingBackward = false, // Default to false, Adapter sets actual value
        distance = distance,
        hitbox = hitbox
    )
}

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
