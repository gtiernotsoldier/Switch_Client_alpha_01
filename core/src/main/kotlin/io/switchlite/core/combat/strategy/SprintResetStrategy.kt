package io.switchlite.core.combat.strategy

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState

/**
 * Sprint reset strategy interface.
 * All implementations must be pure and have no Minecraft dependencies.
 */
interface SprintResetStrategy {
    /**
     * Determine if sprint should be reset in the current state.
     * @param player Current player state
     * @param target Current target state (nullable)
     * @param config Sprint reset configuration
     * @return true if sprint should be reset, false otherwise
     */
    fun shouldResetSprint(
        player: PlayerState,
        target: TargetState?,
        config: SprintResetConfig
    ): Boolean
}

/**
 * Sprint reset mode enumeration.
 */
enum class SprintResetMode {
    NORMAL,     // Standard reset after attack
    NOSTOP,     // Continuous sprint with resets
    SILENT      // Silent reset (server-side only)
}

/**
 * Sprint reset configuration data class.
 */
data class SprintResetConfig(
    val mode: SprintResetMode,
    val probability: io.switchlite.core.option.ProbabilityOption,
    val timing: io.switchlite.core.option.TimingOptions,
    val trigger: io.switchlite.core.option.TriggerOptions
)
