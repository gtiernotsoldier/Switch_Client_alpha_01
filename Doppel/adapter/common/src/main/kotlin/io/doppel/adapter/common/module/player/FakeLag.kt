package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import kotlin.math.abs

/**
 * FakeLag — hold the connection so the server stops hearing about you for a moment.
 *
 * Built on the outbound hold the AutoBlock Lag mode introduced: while the module asks for it, packets are
 * queued with their promise instead of written, and go out together when the hold ends.
 *
 * ## The two modes
 *
 * **Latency** is a steady simulated ping: hold for [delayMs], release, wait [recoilMs], repeat. Predictable,
 * and it is what "the server sees you late" means.
 *
 * **Dynamic** only lags while there is something to lag for — during a fight, with a target — and behaves
 * normally the rest of the time. Fewer held packets, fewer windows in which something can go wrong, which
 * is why it is the default.
 *
 * ## Why it is shaped rather than switched
 *
 * A fake lag only reads as real if it does not sit on a metronome, and it is only safe if it never holds
 * through something that matters. Both properties are taken from LiquidBounce's design:
 *
 *  * **flush on anything dangerous** — a container open, damage taken, an attack (physical or synthetic),
 *    or a target inside [flushDistance]. None of those may be delayed: a held attack is a lost hit, and a
 *    held knockback response is a lost fight.
 *  * **[recoilMs] after every release**, so the connection never stays held for long.
 *  * **[pauseOnNoMove]** releases while standing still, where a real connection would be idle anyway.
 *  * the platform caps a single hold, so a module that dies mid-lag cannot stall the connection, and
 *    [delayMs] stays under that cap.
 */
object FakeLag : Module("FakeLag", Category.PLAYER) {

    private val mode by choices("Mode", arrayOf("Dynamic", "Latency"))

    /** How long one hold lasts. Kept under the platform's cap so a held queue is always released. */
    private val delayMs by int("Delay", 250, 50..2000, "ms")

    /** Quiet time after a release before the next hold — the connection is never held continuously. */
    private val recoilMs by int("Recoil", 750, 0..2000, "ms")

    /** Flush when a target is this close: lagging while someone is in your face is a lost hit. */
    private val flushDistance by float("FlushDistance", 3.5f, 0f..6f, "blocks")

    /** Flush the moment an attack goes out (physical click or a synthetic one from AutoClicker). */
    private val blinkOnAction by boolean("BlinkOnAction", true)

    /** Release while not moving at all — an idle connection has nothing to lag. */
    private val pauseOnNoMove by boolean("PauseOnNoMove", true)

    private var holdStartNano: Long = 0L
    private var lastReleaseNano: Long = 0L

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()

        // Anything below must not be delayed, so the hold ends before it can be.
        val attacking = blinkOnAction && (player.isAttackKeyDown || EventBridge.syntheticAttack)
        val enemyClose = flushDistance > 0f && target != null && target.distance <= flushDistance
        val stationary = pauseOnNoMove && abs(player.motionX) + abs(player.motionZ) < 0.01
        val dangerous = EventBridge.isGuiOpen || player.hurtTime > 0 || attacking || enemyClose || stationary
        if (dangerous) {
            flush(now)
            return
        }

        // Dynamic lags only while there is a fight to lag for; Latency always.
        if (mode == "Dynamic" && target == null) {
            flush(now)
            return
        }

        // Recoil: after a release the connection is quiet for a while, so holds are never back-to-back.
        if (now - lastReleaseNano < recoilMs * 1_000_000L) {
            release()
            return
        }

        if (holdStartNano == 0L) {
            holdStartNano = now
            EventBridge.packetLagActive = true
            markWorked() // a hold starting is a transition, not the steady state
        } else {
            // Keep the request refreshed so the platform's cap does not cut the hold short early.
            EventBridge.packetLagActive = true
        }

        if (now - holdStartNano >= delayMs * 1_000_000L) flush(now)
    }

    /** End the hold and let everything queued go out. */
    private fun flush(now: Long) {
        val wasHolding = holdStartNano != 0L
        release()
        if (wasHolding) lastReleaseNano = now
    }

    private fun release() {
        holdStartNano = 0L
        EventBridge.packetLagActive = false
    }

    override fun onEnable() {
        holdStartNano = 0L
        lastReleaseNano = 0L
        EventBridge.packetLagActive = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Hand the queue back: the flag going false is what makes the platform flush what it held.
        release()
    }
}
