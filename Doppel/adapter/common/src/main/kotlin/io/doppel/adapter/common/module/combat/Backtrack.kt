package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import kotlin.math.max
import kotlin.random.Random

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

    private var holdStartNano: Long = 0L
    private var pauseUntilNano: Long = 0L
    private var windowNanos: Long = 150_000_000L

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // Never held through any of these — the reference's "do not delay" list, in this client's terms.
        val mustRelease = EventBridge.isGuiOpen || player.health <= 0f || player.hurtTime > 0

        val inRange = when (mode) {
            "Legacy" -> target != null && target.distance <= distance
            else -> target != null && (!smart || target.distance <= 6f)
        }

        if (mustRelease || !inRange) {
            release()
            return
        }

        // Pulse: after a window closes, stay quiet for the same length before the next hold.
        if (mode == "Modern" && style == "Pulse" && now < pauseUntilNano) {
            release()
            return
        }

        if (holdStartNano == 0L) {
            holdStartNano = now
            if (mode == "Modern") {
                // A fresh window every time: an evenly spaced hold is the one thing a real connection
                // never produces, and it is also what makes a pattern detectable.
                val lo = minOf(minDelay, maxDelay)
                val hi = maxOf(minDelay, maxDelay)
                windowNanos = Random.nextInt(lo, hi + 1).toLong() * 1_000_000L
            } else {
                windowNanos = 250_000_000L
            }
            markWorked() // starting a hold is the work; the steady state is not
        }
        EventBridge.backtrackActive = true

        if (now - holdStartNano >= windowNanos) {
            release()
            if (mode == "Modern" && style == "Pulse") pauseUntilNano = now + windowNanos
        }
    }

    private fun release() {
        if (holdStartNano != 0L) holdStartNano = 0L
        // The flag going false is what makes the platform deliver everything it held, in order.
        EventBridge.backtrackActive = false
    }

    override fun onEnable() {
        holdStartNano = 0L
        pauseUntilNano = 0L
        windowNanos = 150_000_000L
        EventBridge.backtrackActive = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        release()
    }
}
