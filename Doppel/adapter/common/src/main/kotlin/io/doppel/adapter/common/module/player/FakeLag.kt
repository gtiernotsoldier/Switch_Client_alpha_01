package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState

/**
 * FakeLag — hold the connection so the server stops hearing about you for a moment.
 *
 * Built on the same outbound hold the AutoBlock Lag mode uses: while the module asks for it, packets are
 * queued with their promise instead of being written, and everything goes out together afterwards. The
 * platform caps a hold so a module that dies mid-lag cannot stall the connection, which is why [lagMs]
 * stops below that cap.
 *
 * Realism is the whole point of a fake lag, so it is shaped like latency rather than like a switch:
 *
 *  * **Burst** — alternate: lag for [lagMs], then behave normally for [intervalMs]. This is what a
 *    stuttering connection looks like, and it is not a fixed period an observer can lock onto.
 *  * **Continuous** — one long hold, for when a single hiccup is wanted instead of a rhythm.
 *  * the burst start is jittered by up to [jitterPct] of the interval, because a perfectly even
 *    rhythm is the one thing real packet loss never produces.
 *  * [onlyOnGround] keeps it out of knockback and jump-reset moments, where a real connection is busy.
 */
object FakeLag : Module("FakeLag", Category.PLAYER) {

    private val mode by choices("Mode", arrayOf("Burst", "Continuous"))

    /** How long one hold lasts. Kept under the platform cap so a held queue is always released. */
    private val lagMs by int("LagMs", 250, 50..2000, "ms")

    /** Burst mode: how long to behave normally between holds. 0 = immediately again. */
    private val intervalMs by int("Interval", 750, 0..5000, "ms", modes = arrayOf("Burst"))

    /** Burst mode: randomness applied to the interval, so the rhythm is not a metronome. */
    private val jitterPct by int("Jitter", 25, 0..100, "%", modes = arrayOf("Burst"))

    /** Only lag while standing on a block — a real connection is quietest then, and jumps stay clean. */
    private val onlyOnGround by boolean("OnlyOnGround", false)

    /** The hold does not start until the flag has been released at least once (no stuck state). */
    private var holdStartNano: Long = 0L
    private var nextBurstNano: Long = 0L

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        val now = System.nanoTime()
        if (onlyOnGround && !player.onGround) {
            release()
            return
        }

        if (holdStartNano != 0L) {
            // Holding: keep the request refreshed so the platform's cap does not cut it short, and end it
            // when the hold has lasted long enough.
            EventBridge.packetLagActive = true
            if (now - holdStartNano >= lagMs * 1_000_000L) {
                release()
                nextBurstNano = now + intervalWithJitter()
            }
            return
        }

        if (mode == "Continuous") {
            start(now)
            return
        }
        // Burst: wait out the interval before the next hold.
        if (nextBurstNano == 0L) {
            start(now)
        } else if (now >= nextBurstNano) {
            start(now)
        }
    }

    /** [intervalMs] with up to [jitterPct] taken off it: a real stutter is never on a schedule. */
    internal fun intervalWithJitter(): Long {
        if (jitterPct <= 0) return intervalMs * 1_000_000L
        val cut = (intervalMs.toLong() * jitterPct / 100.0 * Math.random()).toLong()
        return ((intervalMs - cut).coerceAtLeast(0)) * 1_000_000L
    }

    private fun start(now: Long) {
        holdStartNano = now
        EventBridge.packetLagActive = true
        markWorked() // a hold starting is a transition, not the steady state
    }

    private fun release() {
        holdStartNano = 0L
        EventBridge.packetLagActive = false
    }

    override fun onEnable() {
        holdStartNano = 0L
        nextBurstNano = 0L
        EventBridge.packetLagActive = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Hand the queue back: the flag going false is what makes the platform flush everything it held.
        release()
        nextBurstNano = 0L
    }
}
