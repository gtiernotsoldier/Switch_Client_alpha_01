package io.switchlite.core.combat.strategy

import io.switchlite.core.combat.model.PlayerState

/**
 * Sprint reset strategy interface
 * Defines sprint reset timing for knockback reduction
 */
interface SprintResetStrategy {
    /**
     * Calculate optimal sprint reset timing
     * @param player Current player state
     * @param config Sprint reset configuration
     * @return True if sprint should be reset this tick
     */
    fun shouldResetSprint(
        player: PlayerState,
        config: SprintResetConfig
    ): Boolean
}

/**
 * Sprint reset configuration options
 */
data class SprintResetConfig(
    val mode: SprintResetMode,
    val resetDelayTicks: Int,
    val onlyWhenHit: Boolean,
    val chance: Int,
    val triggerOptions: TriggerOptions
)

enum class SprintResetMode {
    LEGIT,      // Conditional reset with delay
    INSTANT,    // Immediate reset on hit
    DISABLED    // No sprint reset
}
