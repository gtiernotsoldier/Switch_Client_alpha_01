package io.doppel.adapter.common.module.player

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean

/**
 * AutoTool — automatically switches to the best hotbar tool for mining.
 *
 * On block click: scans hotbar (0-8) for the item with the highest
 * digging speed for the block under the crosshair. If a better tool
 * exists, silently switches to that slot.
 *
 * With SwitchBack enabled, returns to the original slot when mining stops.
 * OnlySneaking limits tool switching to when Shift is held.
 */
object AutoTool : Module("AutoTool", Category.PLAYER) {

    private val switchBack by boolean("SwitchBack", false)
    private val onlySneaking by boolean("OnlySneaking", false)

    private var wasMining: Boolean = false
    private var originalSlot: Int = -1
    private var switched: Boolean = false

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Edge detection: started mining
        if (player.isMining && !wasMining) {
            onStartMining(player)
        }
        // Edge detection: stopped mining
        if (!player.isMining && wasMining) {
            onStopMining()
        }
        wasMining = player.isMining
    }

    private fun onStartMining(player: PlayerState) {
        // OnlySneaking: must be holding Shift (sprint=off + not moving forward)
        if (onlySneaking && !player.isSneaking) return

        val bestSlot = EventBridge.getBestSlot()
        if (bestSlot < 0 || bestSlot > 8) return

        // Already holding the best tool
        if (bestSlot == player.selectedSlot) return

        // Save original slot before switching
        originalSlot = player.selectedSlot
        EventBridge.switchToSlot(bestSlot)
        switched = true
    }

    private fun onStopMining() {
        if (!switched) return
        if (switchBack && originalSlot in 0..8) {
            EventBridge.switchToSlot(originalSlot)
        }
        switched = false
        originalSlot = -1
    }

    override fun onEnable() {
        wasMining = false
        switched = false
        originalSlot = -1
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        if (switched && originalSlot in 0..8) {
            EventBridge.switchToSlot(originalSlot)
        }
        switched = false
    }
}
