package io.switchlite.core.combat.strategy

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.util.Vec3

/**
 * Aim strategy interface
 * Defines how aim assistance modifies player rotation
 */
interface AimStrategy {
    /**
     * Calculate modified rotation
     * @param currentRotation Current player rotation (yaw, pitch)
     * @param targetRotation Ideal rotation to face target
     * @param player Current player state
     * @param target Target state (nullable)
     * @param config Aim configuration
     * @return Modified rotation with human-like characteristics
     */
    fun modifyRotation(
        currentRotation: Vec2,
        targetRotation: Vec2,
        player: PlayerState,
        target: TargetState?,
        config: AimConfig
    ): Vec2
}

/**
 * 2D vector for rotation (yaw, pitch)
 */
data class Vec2(val yaw: Float, val pitch: Float)

/**
 * Aim configuration options
 */
data class AimConfig(
    val mode: AimMode,
    val smoothness: Float,
    val reactionDelayMs: Int,
    val errorMargin: ClosedFloatingPointRange<Float>,
    val onlyWhenLookingNearTarget: Boolean,
    val maxAngleDifference: Float,
    val triggerOptions: TriggerOptions
)

enum class AimMode {
    LEGIT,           // Soft correction with human limits
    SMOOTH,          // Smooth tracking with noise
    INSTANT          // Instant snap (not recommended)
}
