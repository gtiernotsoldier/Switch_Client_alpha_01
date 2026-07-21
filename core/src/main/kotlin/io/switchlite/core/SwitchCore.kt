package io.switchlite.core

import io.switchlite.core.combat.strategy.AimStrategy
import io.switchlite.core.combat.strategy.SprintResetStrategy
import io.switchlite.core.combat.strategy.VelocityStrategy
import io.switchlite.core.combat.impl.DefaultAimStrategy
import io.switchlite.core.combat.impl.DefaultVelocityStrategy
import io.switchlite.core.combat.impl.DefaultSprintResetStrategy
import io.switchlite.core.network.PacketScheduler
import io.switchlite.core.network.NoOpPacketScheduler

/**
 * Core entry point for Sandwich architecture.
 * Holds all strategy instances and provides global access.
 */
object SwitchCore {
    
    // Strategy instances (can be replaced for testing or customization)
    var velocityStrategy: VelocityStrategy = DefaultVelocityStrategy()
    var aimStrategy: AimStrategy = DefaultAimStrategy()
    var sprintResetStrategy: SprintResetStrategy = DefaultSprintResetStrategy()
    
    // Packet scheduler (must be provided by adapter layer)
    var packetScheduler: PacketScheduler = NoOpPacketScheduler()
    
    /**
     * Reset all strategies to initial state.
     * Call when modules are disabled or reloaded.
     */
    fun reset() {
        if (velocityStrategy is DefaultVelocityStrategy) {
            (velocityStrategy as DefaultVelocityStrategy).reset()
        }
        if (aimStrategy is DefaultAimStrategy) {
            (aimStrategy as DefaultAimStrategy).reset()
        }
        if (sprintResetStrategy is DefaultSprintResetStrategy) {
            (sprintResetStrategy as DefaultSprintResetStrategy).reset()
        }
        packetScheduler.clear()
    }
    
    /**
     * Initialize core with custom strategies.
     */
    fun initialize(
        velocity: VelocityStrategy = DefaultVelocityStrategy(),
        aim: AimStrategy = DefaultAimStrategy(),
        sprintReset: SprintResetStrategy = DefaultSprintResetStrategy(),
        scheduler: PacketScheduler = NoOpPacketScheduler()
    ) {
        velocityStrategy = velocity
        aimStrategy = aim
        sprintResetStrategy = sprintReset
        packetScheduler = scheduler
    }
}
