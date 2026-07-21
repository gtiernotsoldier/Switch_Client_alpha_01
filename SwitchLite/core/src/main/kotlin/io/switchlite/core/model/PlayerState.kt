package io.switchlite.core.model

/**
 * Snapshot of player state at a specific tick.
 * Pure data class, no Minecraft dependencies.
 */
data class PlayerState(
    val name: String,
    val posX: Double,
    val posY: Double,
    val posZ: Double,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val rotationYaw: Float,
    val rotationPitch: Float,
    val onGround: Boolean,
    val isMovingForward: Boolean,
    val isSprinting: Boolean,
    val health: Float,
    val hurtTime: Int,
    val ticks: Long
)
