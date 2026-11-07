package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.contract.logging.CoreLogger
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState

/**
 * AutoTool — hold the best hotbar tool for whatever is under the crosshair.
 *
 * Copied in shape from the reference clients (Nemui's is the clearest at 80 lines, raven-bs's adds the
 * conditions and lists around the same loop), because the shape is what makes it work:
 *
 *  * the check runs **every tick while the attack key is held**, not on a "started mining" edge. An edge
 *    depends on the sampled state changing at exactly the right moment, and a missed edge means the
 *    module never does anything at all — which is precisely how this one behaved;
 *  * the "is the crosshair even on a block" test lives **inside** the search: the platform returns -1 for
 *    no block or no better tool, so the module does not need a separate flag to be right about it;
 *  * the natural baseline is the tool **already held**, not the bare hand, so holding a stone pickaxe on
 *    dirt still gives way to a shovel;
 *  * releasing the attack key (or the clicker stopping) puts the old slot back, which is what
 *    SwitchBack means here.
 *
 * The state line below is deliberately permanent and slow: it prints the trigger and what came back, so
 * "did nothing" can never again be indistinguishable from "nothing to do".
 */
object AutoTool : Module("AutoTool", Category.PLAYER) {

    /** Return to the slot you were on when the attack ends. */
    private val switchBack by boolean("SwitchBack", false)

    /** Only switch while sneaking (raven-bs's "Only while crouching"). */
    private val onlySneaking by boolean("OnlySneaking", false)

    private var originalSlot: Int = -1

    /** Slow diagnostic counter (every ~2 s). */
    private var diag: Int = 0

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // Physical left button or a synthetic click from AutoClicker: both mean "I am digging".
        val attacking = player.isAttackKeyDown || EventBridge.syntheticAttack

        if (attacking && (!onlySneaking || player.isSneaking)) {
            // -1 = no block under the crosshair, or nothing better than what is held.
            val bestSlot = EventBridge.getBestSlot()
            if (bestSlot in 0..8 && bestSlot != player.selectedSlot) {
                if (originalSlot < 0) originalSlot = player.selectedSlot
                EventBridge.switchToSlot(bestSlot)
                markWorked() // the switch it actually made is the work
            }
            if (++diag % 40 == 0) {
                CoreLogger.info(
                    "[AutoTool] attacking=true best=$bestSlot held=${player.selectedSlot} " +
                        "weapon=${player.weaponType} sneaking=${player.isSneaking}"
                )
            }
        } else if (originalSlot >= 0) {
            if (switchBack && originalSlot in 0..8) EventBridge.switchToSlot(originalSlot)
            originalSlot = -1
        }
    }

    override fun onEnable() {
        originalSlot = -1
        diag = 0
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        if (switchBack && originalSlot in 0..8) EventBridge.switchToSlot(originalSlot)
        originalSlot = -1
    }
}
