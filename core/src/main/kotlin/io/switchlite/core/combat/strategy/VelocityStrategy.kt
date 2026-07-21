package io.switchlite.core.combat.strategy

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.util.Vec3

/**
 * Velocity modification strategy interface.
 * All implementations must be pure and have no Minecraft dependencies.
 */
interface VelocityStrategy {
    /**
     * Modify the incoming velocity vector.
     * @param original The original velocity from the server
     * @param player Current player state
     * @param target Current target state (nullable)
     * @param config Velocity configuration
     * @return Modified velocity vector
     */
    fun modifyVelocity(
        original: Vec3,
        player: PlayerState,
        target: TargetState?,
        config: VelocityConfig
    ): Vec3
}

/**
 * Velocity mode enumeration.
 */
enum class VelocityMode {
    LEGIT,      // Legitimate reduction with conditions
    DELAY,      // Delay packet processing
    CLICK       // Click-based activation
}

/**
 * Velocity configuration data class.
 */
data class VelocityConfig(
    val mode: VelocityMode,
    val horizontalRange: io.switchlite.core.option.RandomRange,
    val verticalRange: io.switchlite.core.option.RandomRange,
    val probability: io.switchlite.core.option.ProbabilityOption,
    val timing: io.switchlite.core.option.TimingOptions,
    val trigger: io.switchlite.core.option.TriggerOptions
)
