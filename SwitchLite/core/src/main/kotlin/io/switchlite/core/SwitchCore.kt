package io.switchlite.core

import io.switchlite.core.combat.strategy.AimStrategy
import io.switchlite.core.combat.strategy.VelocityStrategy
import io.switchlite.core.combat.strategy.SprintResetStrategy
import io.switchlite.core.combat.impl.DefaultAimStrategy
import io.switchlite.core.combat.impl.DefaultVelocityStrategy
import io.switchlite.core.combat.impl.DefaultSprintResetStrategy
import io.switchlite.core.network.PacketScheduler

/**
 * Sandwich Architecture - Core Library Entry Point
 * Layer 3: Pure mathematical algorithms, strategies, condition engine
 * Zero Minecraft dependencies
 */
object SwitchCore {
    
    // Strategy instances
    val aimStrategy: AimStrategy = DefaultAimStrategy()
    val velocityStrategy: VelocityStrategy = DefaultVelocityStrategy()
    val sprintResetStrategy: SprintResetStrategy = DefaultSprintResetStrategy()
    
    // Global systems
    val packetScheduler: PacketScheduler = PacketScheduler()
    
    /**
     * Initialize core systems
     */
    fun initialize() {
        println("[SwitchCore] Initialized")
        println("[SwitchCore] Strategies loaded:")
        println("  - AimStrategy: ${aimStrategy::class.simpleName}")
        println("  - VelocityStrategy: ${velocityStrategy::class.simpleName}")
        println("  - SprintResetStrategy: ${sprintResetStrategy::class.simpleName}")
    }
    
    /**
     * Load configuration from JSON
     */
    fun loadConfig(configPath: String) {
        // TODO: Implement JSON config loading
        println("[SwitchCore] Loading config from: $configPath")
    }
}
