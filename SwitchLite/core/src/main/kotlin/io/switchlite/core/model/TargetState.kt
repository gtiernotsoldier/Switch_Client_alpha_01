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
    val hurtTime: Int,
    val isMovingBackward: Boolean,
    val isGoingBack: Boolean,
    val isMovingTowardsPlayer: Boolean,
    val distance: Float,
    val hitbox: Hitbox,
    val id: Int
) {
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
        isMovingBackward = false,
        isGoingBack = false,
        isMovingTowardsPlayer = false,
        distance = distance,
        hitbox = hitbox,
        id = entityId
    )

    companion object {
        val EMPTY = TargetState(
            entityId = 0,
            name = "",
            position = Vec3.ZERO,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0,
            health = 0f,
            hurtTime = 0,
            isMovingBackward = false,
            isGoingBack = false,
            isMovingTowardsPlayer = false,
            distance = 0f,
            hitbox = Hitbox(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            id = 0
        )
    }
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
