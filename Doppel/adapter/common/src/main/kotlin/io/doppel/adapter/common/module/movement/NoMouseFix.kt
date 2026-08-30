package io.doppel.adapter.common.module.movement

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category

/**
 * NoMouseFix — protect mouse input from server packet interference.
 *
 * Some anti-cheat servers attempt to detect abnormal mouse input during
 * velocity packet callbacks. This module snapshots mouse delta + sensitivity
 * at the START of each tick and restores them at the END if a velocity
 * packet was received — preventing the server from seeing tampered input.
 *
 * No configuration. Client-local, no packets sent.
 */
object NoMouseFix : Module("NoMouseFix", Category.MOVEMENT) {

    private val startListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        // Snapshot mouse state before any server packet can interfere
        EventBridge.snapMouseDeltaX = EventBridge.mouseDeltaX
        EventBridge.snapMouseDeltaY = EventBridge.mouseDeltaY
        EventBridge.snapMouseSensitivity = EventBridge.mouseSensitivity
    }

    private val endListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (EventBridge.velocityPacketReceivedThisTick) {
            EventBridge.mouseDeltaX = EventBridge.snapMouseDeltaX
            EventBridge.mouseDeltaY = EventBridge.snapMouseDeltaY
            EventBridge.mouseSensitivity = EventBridge.snapMouseSensitivity
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
