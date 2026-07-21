package io.switchlite.core.condition

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.option.TriggerOptions

/**
 * Unified condition checker for all modules.
 * Evaluates trigger conditions against game state.
 */
object ConditionChecker {
    
    /**
     * Check if all trigger conditions are satisfied.
     * @param trigger The trigger options to evaluate
     * @param player Current player state
     * @param target Current target state (nullable)
     * @return true if all conditions pass, false otherwise
     */
    fun check(trigger: TriggerOptions, player: PlayerState, target: TargetState?): Boolean {
        // Ground/Air checks
        if (trigger.onlyGround && !player.isOnGround) return false
        if (trigger.onlyAir && player.isOnGround) return false
        if (trigger.disabledInAir && !player.isOnGround) return false
        
        // Movement checks
        if (trigger.onlyMove && !player.isMoving) return false
        if (trigger.onlyMoveForward && !player.isMovingForward) return false
        if (trigger.onlyMoveBackward && player.isMovingForward) return false
        
        // Target-based checks
        if (trigger.onlyWhenTargetGoesBack) {
            if (target == null || !target.isMovingBackward) return false
        }
        
        // Distance checks
        if (target != null) {
            val distance = target.distanceToPlayer.toFloat()
            if (distance < trigger.minDistance) return false
            if (distance > trigger.maxDistance) return false
        }
        
        // Look-based checks (simplified, actual implementation may vary)
        if (trigger.onLook) {
            if (target == null) return false
            // Check if target is in crosshair (simplified)
            val yawDiff = Math.abs(player.yaw - target.yaw) % 360f
            val normalizedYawDiff = if (yawDiff > 180f) 360f - yawDiff else yawDiff
            if (normalizedYawDiff > 90f) return false
        }
        
        return true
    }
}
