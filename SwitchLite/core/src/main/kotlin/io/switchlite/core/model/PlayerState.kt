package io.switchlite.core.model

import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3

/**
 * Snapshot of player state at a specific tick.
 * Pure data class, no Minecraft dependencies.
 */
data class PlayerState(
    val name: String,
    val position: Vec3,
    val rotation: Vec2,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val onGround: Boolean,
    val isMoving: Boolean,
    val isMovingForward: Boolean,
    val isSprinting: Boolean,
    val health: Float,
    val hurtTime: Int,
    val maxHurtResistantTime: Int,
    val isBlocking: Boolean,
    val isUsingItem: Boolean,
    val isLookingAtTarget: Boolean,
    val isMining: Boolean,
    val weaponType: WeaponType,
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
        rotation = Vec2(rotationYaw, rotationPitch),
        motionX = motionX, motionY = motionY, motionZ = motionZ,
        onGround = onGround,
        isMoving = (motionX != 0.0 || motionZ != 0.0),
        isMovingForward = isMovingForward,
        isSprinting = isSprinting,
        health = health,
        hurtTime = hurtTime,
        maxHurtResistantTime = 10,
        isBlocking = false,
        isUsingItem = false,
        isLookingAtTarget = false,
        isMining = false,
        weaponType = WeaponType.OTHER,
        ticks = ticks
    )

    companion object {
        val EMPTY = PlayerState(
            name = "",
            position = Vec3.ZERO,
            rotation = Vec2.ZERO,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0,
            onGround = false,
            isMoving = false,
            isMovingForward = false,
            isSprinting = false,
            health = 0f,
            hurtTime = 0,
            maxHurtResistantTime = 10,
            isBlocking = false,
            isUsingItem = false,
            isLookingAtTarget = false,
            isMining = false,
            weaponType = WeaponType.OTHER,
            ticks = 0
        )
    }
}
