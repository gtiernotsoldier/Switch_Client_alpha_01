package io.switchlite.core.combat.strategy

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.util.Vec3
import io.switchlite.core.option.TriggerOptions

/**
 * Velocity strategy interface
 * Defines how knockback is modified
 */
interface VelocityStrategy {
    /**
     * Calculate modified velocity vector
     * @param original Original knockback vector from server
     * @param player Current player state
     * @param target Target state (nullable, for conditional logic)
     * @param config Velocity configuration
     * @return Modified velocity with human-like characteristics
     */
    fun modifyVelocity(
        original: Vec3,
        player: PlayerState,
        target: TargetState?,
        config: VelocityConfig
    ): Vec3
}

/**
 * Velocity configuration options
 */
data class VelocityConfig(
    val mode: VelocityMode,
    val horizontalRange: ClosedFloatingPointRange<Float>,
    val verticalRange: ClosedFloatingPointRange<Float>,
    val chance: Int,
    val delayMs: Int,
    val delayTicks: Int,
    val triggerOptions: TriggerOptions
)

enum class VelocityMode {
    LEGIT,    // Range random + conditional trigger
    DELAY,    // Packet delay simulation
    CLICK     // Auto-clicker integration
}
