package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.AimStrategy
import io.switchlite.core.combat.strategy.Vec2
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.util.MathUtils

/**
 * Default aim strategy implementation
 * Pure mathematical calculation with human-like characteristics
 */
class DefaultAimStrategy : AimStrategy {
    
    override fun modifyRotation(
        currentRotation: Vec2,
        targetRotation: Vec2,
        player: PlayerState,
        target: TargetState?,
        config: io.switchlite.core.combat.strategy.AimConfig
    ): Vec2 {
        // Check trigger conditions
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return currentRotation
        }
        
        // Calculate angle difference
        val yawDiff = MathUtils.angleDifference(currentRotation.yaw, targetRotation.yaw)
        val pitchDiff = MathUtils.angleDifference(currentRotation.pitch, targetRotation.pitch)
        
        // Apply smoothness with human reaction delay
        val smoothFactor = config.smoothness.coerceIn(0.01f, 1.0f)
        
        // Add human error margin
        val errorMargin = MathUtils.randomInRange(config.errorMargin)
        
        // Calculate modified rotation
        var newYaw = currentRotation.yaw + yawDiff * smoothFactor
        var newPitch = currentRotation.pitch + pitchDiff * smoothFactor
        
        // Apply error margin (simulate human imperfection)
        newYaw += errorMargin
        newPitch += errorMargin * 0.5f
        
        // Normalize angles
        newYaw = MathUtils.normalizeAngle(newYaw)
        newPitch = newPitch.coerceIn(-90f, 90f)
        
        return Vec2(newYaw, newPitch)
    }
}
