package io.switchlite.core.combat.impl

import io.switchlite.core.algorithm.GaussianNoise
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.AimConfig
import io.switchlite.core.combat.strategy.AimMode
import io.switchlite.core.combat.strategy.AimStrategy
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import kotlin.random.Random

/**
 * Default implementation of AimStrategy.
 * Implements legitimate aim assistance with human-like behavior.
 */
class DefaultAimStrategy : AimStrategy {
    
    private val noiseProvider = GaussianNoise(mean = 0.0, stdDev = 0.15)
    
    // Track state for overshoot and callback
    private var isOvershooting = false
    private var overshootCounter = 0
    
    override fun calculateAimCorrection(
        player: PlayerState,
        target: TargetState?,
        config: AimConfig
    ): Vec2? {
        // Only process LEGIT and SMOOTH modes
        if (config.mode != AimMode.LEGIT && config.mode != AimMode.SMOOTH) {
            return null
        }
        
        // Require target
        if (target == null) {
            return null
        }
        
        // Check trigger conditions
        if (!ConditionChecker.check(config.trigger, player, target)) {
            return null
        }
        
        // Probability check
        if (!config.probability.test()) {
            return null
        }
        
        // Calculate target point (box edge for soft boundary)
        val playerPos = Vec3(player.posX, player.posY + 1.62, player.posZ)
        val targetPos = Vec3(target.posX, target.posY + target.hitBoxHeight / 2f, target.posZ)
        
        val aimPoint = RotationCalculator.getBoxEdge(
            playerPos,
            targetPos,
            target.hitBoxWidth,
            target.hitBoxHeight
        )
        
        // Calculate required rotation
        val requiredRotation = RotationCalculator.calculateRotation(playerPos, aimPoint)
        
        // Apply smooth angle interpolation with speed limit
        var yawCorrection = RotationCalculator.smoothAngle(
            current = 0f,
            target = requiredRotation.x - player.yaw,
            maxSpeed = config.maxAngularVelocity,
            overshoot = 0f
        )
        
        var pitchCorrection = RotationCalculator.smoothAngle(
            current = 0f,
            target = requiredRotation.y - player.pitch,
            maxSpeed = config.maxAngularVelocity,
            overshoot = 0f
        )
        
        // Handle overshoot behavior (15-25% chance)
        if (config.overshootChance > 0f && Random.nextFloat() < config.overshootChance) {
            if (!isOvershooting) {
                isOvershooting = true
                overshootCounter = 2 // Overshoot for 2 ticks
            }
            
            if (overshootCounter > 0) {
                yawCorrection *= 1.15f // 15% overshoot
                pitchCorrection *= 1.15f
                overshootCounter--
            } else {
                isOvershooting = false
            }
        }
        
        // Apply micro-jitter for human-like imperfection
        yawCorrection = noiseProvider.apply(yawCorrection.toDouble()).toFloat()
        pitchCorrection = noiseProvider.apply(pitchCorrection.toDouble()).toFloat()
        
        return Vec2(yawCorrection, pitchCorrection)
    }
    
    /**
     * Reset internal state (called when module is disabled).
     */
    fun reset() {
        isOvershooting = false
        overshootCounter = 0
    }
}
