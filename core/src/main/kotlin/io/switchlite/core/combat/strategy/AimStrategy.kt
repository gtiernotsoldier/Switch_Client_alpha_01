package io.switchlite.core.combat.strategy

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.util.Vec2

/**
 * Aim assistance strategy interface.
 * All implementations must be pure and have no Minecraft dependencies.
 */
interface AimStrategy {
    /**
     * Calculate aim correction for the current state.
     * @param player Current player state
     * @param target Current target state (nullable)
     * @param config Aim configuration
     * @return Vec2 containing yaw and pitch corrections, or null if no correction needed
     */
    fun calculateAimCorrection(
        player: PlayerState,
        target: TargetState?,
        config: AimConfig
    ): Vec2?
}

/**
 * Aim mode enumeration.
 */
enum class AimMode {
    SMOOTH,     // Smooth rotation towards target
    LEGIT,      // Legitimate assistance with conditions
    SILENT      // Silent rotation (server-side only)
}

/**
 * Aim configuration data class.
 */
data class AimConfig(
    val mode: AimMode,
    val maxAngularVelocity: Float,
    val overshootChance: Float,
    val microJitterStrength: Float,
    val probability: io.switchlite.core.option.ProbabilityOption,
    val timing: io.switchlite.core.option.TimingOptions,
    val trigger: io.switchlite.core.option.TriggerOptions
)
