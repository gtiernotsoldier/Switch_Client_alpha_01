package io.switchlite.core.model

import io.switchlite.core.util.Vec3

/**
 * Snapshot of player state at a specific tick.
 * Pure data class, no Minecraft dependencies.
 */
data class PlayerState(
    val name: String,
    val position: Vec3,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val rotationYaw: Float,
    val rotationPitch: Float,
    val onGround: Boolean,
    val isMoving: Boolean,           // NEW: Whether player is moving
    val isMovingForward: Boolean,    // NEW: Whether player is moving forward
    val isSprinting: Boolean,
    val health: Float,
    val hurtTime: Int,               // NEW: Current hurt time
    val maxHurtTime: Int,            // NEW: Max hurt time (usually 10)
    val isBlocking: Boolean,         // NEW: Whether blocking with sword
    val isLookingAtTarget: Boolean,  // NEW: Whether looking at current target
    val ticks: Long
) {
    // Legacy constructor for backward compatibility
    constructor(
        name: String,
        posX: Double, posY: Double, posZ: Double,
        motionX: Double, motionY: Double, motionZ: Double,
        rotationYaw: Float, rotationPitch: Float,
        onGround: Boolean, isMovingForward: Boolean, isSprinting: Boolean,
        health: Float, hurtTime: Int, ticks: Long
    ) : this(
        name = name,
        position = Vec3(posX, posY, posZ),
        motionX = motionX, motionY = motionY, motionZ = motionZ,
        rotationYaw = rotationYaw, rotationPitch = rotationPitch,
        onGround = onGround,
        isMoving = (motionX != 0.0 || motionZ != 0.0),
        isMovingForward = isMovingForward,
        isSprinting = isSprinting,
        health = health,
        hurtTime = hurtTime,
        maxHurtTime = 10,
        isBlocking = false,
        isLookingAtTarget = false,
        ticks = ticks
    )
}
