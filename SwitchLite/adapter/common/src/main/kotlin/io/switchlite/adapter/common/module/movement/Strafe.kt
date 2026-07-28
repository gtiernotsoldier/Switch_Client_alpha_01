package io.switchlite.adapter.common.module.movement

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * Strafe — instantly stop horizontal inertia when no movement input.
 *
 * Vanilla Minecraft preserves momentum after releasing WASD (inertia slide).
 * Strafe zeros motionX/motionZ on the frame the player stops pressing
 * all movement keys, giving pixel-perfect stops.
 *
 * No configuration. Client-local, no packets, anti-cheat safe.
 * Compatible with Sprint, WTap, STap, Velocity.
 */
object Strafe : Module("Strafe", Category.MOVEMENT) {

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Check all four movement keys — skip if any are pressed
        if (player.isMovingForward) return
        if (EventBridge.isKeyBackDown) return
        if (EventBridge.isKeyLeftDown) return
        if (EventBridge.isKeyRightDown) return

        // No input — kill inertia
        if (player.motionX != 0.0 || player.motionZ != 0.0) {
            EventBridge.applyMotion(Vec3(0.0, player.motionY, 0.0))
        }
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
