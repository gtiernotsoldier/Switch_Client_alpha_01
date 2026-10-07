package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState

/**
 * Backtrack — hold the newest entity position updates, so what you see (and resolve against) is where the
 * server said they were a moment ago.
 *
 * This is LiquidBounce's network half: the incoming S14/S18 updates are queued by the platform instead of
 * being delivered, and released together when the window closes. LiquidBounce also redirects its attacks
 * onto the recorded position; that part is deliberately NOT here — it needs the platform to resolve hit
 * boxes out of history, which is a separate change to the combat path rather than a packet hold, and
 * pretending otherwise would ship a module whose name promises something it does not do.
 *
 * What is here is bounded the way a network trick has to be:
 *
 *  * [maxDelay] is the window, and the platform caps a hold independently, so entities can never freeze;
 *  * damage taken, a screen opening, or losing the target ([onlyInCombat]) release immediately — a frozen
 *    opponent you cannot reach is worse than a fresh one you can;
 *  * released packets are delivered in the order the server sent them, so the game never applies them out
 *    of order.
 */
object Backtrack : Module("Backtrack", Category.COMBAT) {

    /** How long entities may stay at their previous reported position. */
    private val maxDelay by int("MaxDelay", 250, 50..500, "ms")

    /** Only hold while a target is in front of you; idle in a fight-free world instead. */
    private val onlyInCombat by boolean("OnlyInCombat", true)

    private var holdStartNano: Long = 0L

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // Frozen entities are only acceptable while there is a fight; otherwise a real update is better.
        val release = EventBridge.isGuiOpen ||
            player.hurtTime > 0 ||
            (onlyInCombat && target == null) ||
            (holdStartNano != 0L && now - holdStartNano >= maxDelay * 1_000_000L)

        if (release) {
            if (holdStartNano != 0L) {
                holdStartNano = 0L
                EventBridge.backtrackActive = false
            }
            return
        }

        if (holdStartNano == 0L) {
            holdStartNano = now
            markWorked() // starting a hold is the work; the steady state is not
        }
        EventBridge.backtrackActive = true
    }

    override fun onEnable() {
        holdStartNano = 0L
        EventBridge.backtrackActive = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // The flag going false is what makes the platform deliver everything it held.
        holdStartNano = 0L
        EventBridge.backtrackActive = false
    }
}
