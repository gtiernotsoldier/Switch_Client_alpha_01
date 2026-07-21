package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.SprintResetConfig
import io.switchlite.core.combat.strategy.SprintResetMode
import io.switchlite.core.combat.strategy.SprintResetStrategy
import io.switchlite.core.condition.ConditionChecker
import kotlin.random.Random

/**
 * Default implementation of SprintResetStrategy.
 * Implements legitimate sprint reset with human-like behavior.
 */
class DefaultSprintResetStrategy : SprintResetStrategy {
    
    // Track delay state
    private var pendingDelayTicks = 0
    private var isDelayed = false
    
    override fun shouldResetSprint(
        player: PlayerState,
        target: TargetState?,
        config: SprintResetConfig
    ): Boolean {
        // Only process NORMAL and NOSTOP modes
        if (config.mode != SprintResetMode.NORMAL && config.mode != SprintResetMode.NOSTOP) {
            return false
        }
        
        // Check trigger conditions
        if (!ConditionChecker.check(config.trigger, player, target)) {
            return false
        }
        
        // Probability check
        if (!config.probability.test()) {
            return false
        }
        
        // Handle reaction delay simulation
        if (pendingDelayTicks > 0) {
            pendingDelayTicks--
            return false // Don't trigger yet
        } else if (!isDelayed) {
            // Sample initial delay
            pendingDelayTicks = config.timing.sampleDelayTicks()
            isDelayed = true
            if (pendingDelayTicks > 0) {
                return false
            }
        }
        
        // Reset delay flag for next attack
        isDelayed = false
        
        // For NOSTOP mode, always return true when conditions are met
        if (config.mode == SprintResetMode.NOSTOP) {
            return true
        }
        
        // For NORMAL mode, only reset if player is sprinting and moving forward
        return player.isSprinting && player.isMovingForward
    }
    
    /**
     * Reset internal state (called when module is disabled).
     */
    fun reset() {
        pendingDelayTicks = 0
        isDelayed = false
    }
}
