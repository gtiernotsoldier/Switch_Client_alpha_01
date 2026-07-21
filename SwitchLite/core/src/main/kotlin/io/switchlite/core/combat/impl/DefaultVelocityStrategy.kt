package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.VelocityStrategy
import io.switchlite.core.combat.strategy.VelocityMode
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.util.MathUtils
import io.switchlite.core.util.Vec3

/**
 * Default velocity strategy implementation
 * Pure mathematical calculation with human-like knockback reduction
 */
class DefaultVelocityStrategy : VelocityStrategy {
    
    private val random = kotlin.random.Random
    
    override fun modifyVelocity(
        original: Vec3,
        player: PlayerState,
        target: TargetState?,
        config: io.switchlite.core.combat.strategy.VelocityConfig
    ): Vec3 {
        // Only process LEGIT mode for now
        if (config.mode != VelocityMode.LEGIT) {
            return original
        }
        
        // Check trigger conditions
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return original
        }
        
        // Chance check
        if (random.nextInt(100) >= config.chance) {
            return original
        }
        
        // Generate random retention ratios within configured range
        val horizontalRatio = MathUtils.randomInRange(config.horizontalRange)
        val verticalRatio = MathUtils.randomInRange(config.verticalRange)
        
        // Apply retention ratios
        val newX = original.x * horizontalRatio
        val newY = original.y * verticalRatio
        val newZ = original.z * horizontalRatio
        
        return Vec3(newX, newY, newZ)
    }
}
