package io.switchlite.adapter.common.module.movement

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * NoKeyboardFix — protect keyboard input from server packet interference.
 *
 * Some servers attempt to reset key bind states (attack, movement) during
 * velocity or position-correction packets to detect unusual input patterns.
 * This module snapshots WASD key states at START of each tick and restores
 * them at END if a velocity packet was received.
 *
 * No configuration. Client-local, no packets sent.
 */
object NoKeyboardFix : Module("NoKeyboardFix", Category.MOVEMENT) {

    private val startListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        EventBridge.snapKeyForward = EventBridge.isKeyForwardDown
        EventBridge.snapKeyBack = EventBridge.isKeyBackDown
        EventBridge.snapKeyLeft = EventBridge.isKeyLeftDown
        EventBridge.snapKeyRight = EventBridge.isKeyRightDown
    }

    private val endListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (EventBridge.velocityPacketReceivedThisTick) {
            EventBridge.isKeyForwardDown = EventBridge.snapKeyForward
            EventBridge.isKeyBackDown = EventBridge.snapKeyBack
            EventBridge.isKeyLeftDown = EventBridge.snapKeyLeft
            EventBridge.isKeyRightDown = EventBridge.snapKeyRight
            EventBridge.velocityPacketReceivedThisTick = false
        }
    }

    override fun onEnable() {
        EventBridge.registerStartTickListener(startListener)
        EventBridge.registerTickListener(endListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        EventBridge.unregisterTickListener(endListener)
    }
}
