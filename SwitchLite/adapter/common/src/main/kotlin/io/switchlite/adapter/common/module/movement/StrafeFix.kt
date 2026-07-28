package io.switchlite.adapter.common.module.movement

import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import kotlin.math.hypot

/**
 * StrafeFix — corrects WASD movement direction lag when rotating the camera.
 *
 * In vanilla Minecraft, movement direction is computed using the previous
 * frame's rotation — causing a one-frame "drift" when flicking the mouse.
 * StrafeFix recalculates movement using the current rotation every tick.
 *
 * No configuration. Client-local only. Safe under all anti-cheat systems
 * (no abnormal packets, no speed modification).
 */
object StrafeFix : Module("StrafeFix", Category.MOVEMENT) {

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // At least one movement key must be held
        val forward = if (player.isMovingForward) 1f else 0f
        val back = if (EventBridge.isKeyBackDown) -1f else 0f
        val left = if (EventBridge.isKeyLeftDown) -1f else 0f
        val right = if (EventBridge.isKeyRightDown) 1f else 0f

        val moveStrafe = left + right
        val moveForward = forward + back

        // No movement input — nothing to correct
        if (moveForward == 0f && moveStrafe == 0f) return

        // Current horizontal speed
        val speed = hypot(player.motionX, player.motionZ)
        if (speed < 0.01) return

        // Compute corrected direction from current yaw
        val facing = RotationCalculator.yawToDirection(player.rotation.yaw)
        // Perpendicular vector (rotate 90° CCW): (-facingZ, facingX)
        val strafeX = -facing.z
        val strafeZ = facing.x

        // Normalize combined direction
        val dirX = facing.x * moveForward + strafeX * moveStrafe
        val dirZ = facing.z * moveForward + strafeZ * moveStrafe
        val dirLen = hypot(dirX, dirZ)
        if (dirLen < 0.001) return

        // Apply corrected motion (preserve speed + vertical)
        EventBridge.applyMotion(Vec3(
            dirX / dirLen * speed,
            player.motionY,
            dirZ / dirLen * speed
        ))
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
