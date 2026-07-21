package io.switchlite.core.condition

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.option.TriggerOptions

/**
 * Unified condition checker for all trigger options
 * Compiles TriggerOptions into optimized check functions
 */
object ConditionChecker {
    
    /**
     * Check if all conditions are met for activation
     */
    fun check(options: TriggerOptions, player: PlayerState, target: TargetState?): Boolean {
        // Ground/Air checks
        if (options.onlyGround && !player.onGround) return false
        if (options.onlyAir && player.onGround) return false
        if (options.disabledInAir && !player.onGround) return false
        
        // Movement checks
        if (options.onlyMove && !player.isMoving) return false
        if (options.onlyMoveForward && !player.isMovingForward) return false
        if (options.onlyMoveBackward) return false // TODO: Implement backward detection
        if (options.onlyStrafe) return false // TODO: Implement strafe detection
        
        // Target-based checks
        if (target != null) {
            if (options.onlyWhenTargetGoesBack && !target.isGoingBack) return false
            if (options.onlyWhenTargetApproaches && !target.isMovingTowardsPlayer) return false
            
            // Distance checks
            if (target.distance < options.minDistance) return false
            if (target.distance > options.maxDistance) return false
        } else {
            // If target is required but null, fail
            if (options.onlyWhenTargetGoesBack || options.onlyWhenTargetApproaches) return false
        }
        
        // Look direction check
        if (options.onLook) {
            // TODO: Implement onLook check (requires crosshair alignment)
            return false
        }
        
        // onlyCurrentView check
        if (options.onlyCurrentView && target != null && !player.isLookingAtTarget) return false
        
        // disableOnMine check - simplified, actual implementation may vary
        if (options.disableOnMine && player.isMining) return false
        
        // onlyOnClick check - handled externally via input listeners
        
        // Chance check
        if (options.chance < 100) {
            val random = kotlin.random.Random.nextInt(100)
            if (random >= options.chance) return false
        }
        
        // Delay checks handled externally via tick counters
        
        return true
    }
    
    /**
     * Compile options into a reusable check function
     * For performance optimization (fingerprint caching)
     */
    fun compile(options: TriggerOptions): (PlayerState, TargetState?) -> Boolean {
        return { player, target -> check(options, player, target) }
    }
}
