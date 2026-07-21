package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.strategy.SprintResetStrategy
import io.switchlite.core.combat.strategy.SprintResetMode
import io.switchlite.core.condition.ConditionChecker

/**
 * Default sprint reset strategy implementation
 * Pure mathematical calculation for optimal sprint reset timing
 */
class DefaultSprintResetStrategy : SprintResetStrategy {
    
    private val random = kotlin.random.Random
    
    override fun shouldResetSprint(
        player: PlayerState,
        config: io.switchlite.core.combat.strategy.SprintResetConfig
    ): Boolean {
        // Check if disabled
        if (config.mode == SprintResetMode.DISABLED) {
            return false
        }
        
        // Check trigger conditions
        if (!ConditionChecker.check(config.triggerOptions, player, null)) {
            return false
        }
        
        // Chance check
        if (random.nextInt(100) >= config.chance) {
            return false
        }
        
        // Mode-specific logic
        return when (config.mode) {
            SprintResetMode.LEGIT -> {
                // Only reset when moving and on ground
                player.isMoving && player.onGround
            }
            SprintResetMode.INSTANT -> {
                // Always reset (not recommended for legitimacy)
                true
            }
            SprintResetMode.DISABLED -> false
        }
    }
}
