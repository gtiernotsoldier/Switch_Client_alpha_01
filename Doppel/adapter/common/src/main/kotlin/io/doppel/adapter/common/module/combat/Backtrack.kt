package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.Strategies

/**
 * Backtrack — hold the newest entity position updates, so what you see (and reach for) is where the
 * server said they were a moment ago.
 *
 * The network half of LiquidBounce's Backtrack, including its modes and its judgement about when holding
 * is worth anything. The half that is deliberately absent is the attack redirection onto the recorded
 * position: that needs the combat path to resolve hit boxes out of history, which is a change to how
 * attacks are aimed rather than a packet hold.
 *
 * ## Modes (LiquidBounce's)
 *
 * **Modern** holds for a window that is re-rolled between [minDelay] and [maxDelay] each time, so the
 * hold is not a fixed period anyone can measure. **Legacy** holds while the target is inside
 * [distance] and lets the updates flow as soon as it is not.
 *
 * ## Judgements
 *
 *  * **[smart]** — hold only while the target is close enough for the hold to matter; a target across the
 *    map is not worth a desynced view of.
 *  * **[style] Pulse** — one hold per window and then a pause; **Smooth** keeps holding while the
 *    conditions hold. Pulse is the calmer of the two.
 *  * nothing is held through: **own death**, **damage taken**, a screen opening, or losing the target.
 *    LiquidBounce lists the same things (its own death, the target being removed or its health reaching
 *    zero, hurt/die sounds) as packets it never delays.
 *
 * One deviation worth stating: LiquidBounce cancels *every* received packet during a window, which is
 * what makes it desync-prone. This holds position updates and only position updates — the same effect on
 * what you see and reach for, without freezing chat, sounds, health or the world itself.
 */
object Backtrack : Module("Backtrack", Category.COMBAT) {

    private val mode by choices("Mode", arrayOf("Modern", "Legacy"))

    /** Modern: the hold window is re-rolled between these two, so it has no fixed period. */
    private val minDelay by int("MinDelay", 80, 50..500, "ms", modes = arrayOf("Modern"))
    private val maxDelay by int("MaxDelay", 250, 50..500, "ms", modes = arrayOf("Modern"))

    /** Modern: Pulse holds once per window then pauses; Smooth keeps holding while conditions hold. */
    private val style by choices("Style", arrayOf("Smooth", "Pulse"), modes = arrayOf("Modern"))

    /** Modern: only hold when the target is within this many blocks. */
    private val smart by boolean("Smart", true, modes = arrayOf("Modern"))

    /** Legacy: hold while the target is this close. */
    private val distance by float("Distance", 3.5f, 0f..6f, "blocks", modes = arrayOf("Legacy"))

    /** Last tick's answer, only so a new hold can flash the HUD once. */
    private var holding: Boolean = false

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // Never held through any of these — the reference's "do not delay" list, in this client's terms.
        val mustRelease = EventBridge.isGuiOpen || player.health <= 0f || player.hurtTime > 0

        // The window itself lives in the core: it is rolled, expired and paused
        // there. This side only reports the clock and the conditions.
        val hold = Strategies.backtrackTick(
            player,
            target,
            System.nanoTime(),
            modern = mode != "Legacy",
            pulse = style == "Pulse",
            smart = smart,
            minDelayMs = minDelay,
            maxDelayMs = maxDelay,
            distance = target?.distance ?: -1f,
            legacyDistance = distance,
            mustRelease = mustRelease
        )

        // No answer: let the updates through. Holding is the state that can
        // desync the view, so the safe direction when the core is unreachable is
        // not to hold at all.
        if (hold == null) {
            release()
            return
        }

        if (hold && !holding) markWorked() // starting a hold is the work; the steady state is not
        holding = hold
        EventBridge.backtrackActive = hold
    }

    private fun release() {
        holding = false
        // The flag going false is what makes the platform deliver everything it held, in order.
        EventBridge.backtrackActive = false
    }

    override fun onEnable() {
        holding = false
        EventBridge.backtrackActive = false
        // A hold the core still remembers would keep packets queued for a module
        // that now believes it is holding nothing.
        Strategies.resetPlayer()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        Strategies.resetPlayer()
        release()
    }
}
