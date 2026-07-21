package io.switchlite.core.combat.impl

import io.switchlite.core.algorithm.GaussianNoise
import io.switchlite.core.algorithm.VectorOperations
import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.VelocityConfig
import io.switchlite.core.combat.strategy.VelocityMode
import io.switchlite.core.combat.strategy.VelocityStrategy
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.util.Vec3
import kotlin.random.Random

/**
 * Default implementation of VelocityStrategy.
 * Implements legitimate knockback reduction with human-like behavior.
 */
class DefaultVelocityStrategy : VelocityStrategy {
    
    private val noiseProvider = GaussianNoise(mean = 0.0, stdDev = 0.02)
    
    // Track delay state per player tick
    private var pendingDelayTicks = 0
    private var isDelayed = false
    
    override fun modifyVelocity(
        original: Vec3,
        player: PlayerState,
        target: TargetState?,
        config: VelocityConfig
    ): Vec3 {
        // Only process LEGIT mode in this implementation
        if (config.mode != VelocityMode.LEGIT) {
            return original
        }
        
        // Check trigger conditions
        if (!ConditionChecker.check(config.trigger, player, target)) {
            return original
        }
        
        // Probability check
        if (!config.probability.test()) {
            return original
        }
        
        // Handle reaction delay simulation
        if (pendingDelayTicks > 0) {
            pendingDelayTicks--
            return original // Don't apply modification yet
        } else if (!isDelayed) {
            // Sample initial delay
            pendingDelayTicks = config.timing.sampleDelayTicks()
            isDelayed = true
            if (pendingDelayTicks > 0) {
                return original
            }
        }
        
        // Reset delay flag for next hit
        isDelayed = false
        
        // Sample horizontal and vertical retention rates
        val hRetention = config.horizontalRange.sample().toDouble() / 100.0
        val vRetention = config.verticalRange.sample().toDouble() / 100.0
        
        // Apply scaling
        var modified = VectorOperations.scaleHorizontalVertical(original, hRetention, vRetention)
        
        // Apply micro-noise for human-like imperfection
        modified = Vec3(
            noiseProvider.apply(modified.x),
            noiseProvider.apply(modified.y),
            noiseProvider.apply(modified.z)
        )
        
        return modified
    }
    
    /**
     * Reset internal state (called when module is disabled).
     */
    fun reset() {
        pendingDelayTicks = 0
        isDelayed = false
    }
}
