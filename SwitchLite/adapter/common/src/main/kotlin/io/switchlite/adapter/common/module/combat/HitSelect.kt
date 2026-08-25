package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int

/**
 * HitSelect — a click selector that decides whether the player's attack click actually fires.
 *
 * Unlike a mod (which can swallow the attack event directly), we are an injector: the background
 * tick only WRITES desired state, and the MAIN thread is the only place that writes the real
 * attack key. So HitSelect works through two cross-thread flags that ForgeEventBridge.applySyntheticInput
 * applies on the render thread:
 *
 *   - [EventBridge.attackAllowed] — when false, the attack key is forced OFF even if the physical
 *     mouse button is held. Used to swallow clicks that would land while the target is still in its
 *     i-frame window (a wasted hit).
 *   - [EventBridge.syntheticAttack] — a one-tick pulse that presses the attack key even though the
 *     player isn't clicking. Used for the counter-attack.
 *
 * Two rules (both on by default):
 *
 * 1. Retiming — while the target is still invulnerable (hurtResistantTime > RetimeAt), clicks are
 *    swallowed; when it drops to RetimeAt the click passes through, so it lands exactly as the
 *    target becomes hittable again. This is the core "don't waste hits on the i-frame" mechanic.
 *
 * 2. CounterHit — when the player is within range, NOT clicking, and just got hit (hurtTime > 0),
 *    fire one automatic counter-attack once (cooldown-gated). The "eat a hit then hit back" trade.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Rule 2: Retiming (swallow clicks inside the target's i-frame window) ==========
    private val retiming by boolean("Retiming", true)
    private val retimeRange by float("RetimeRange", 3.0f, 0.0f..6.0f, "blocks")
    /** Target is considered "about to become hittable" when hurtResistantTime <= this. */
    private val retimeAt by int("RetimeAt", 3, 0..10, "ticks")

    // ========== Rule 1: CounterHit (eat a hit → auto hit back) ==========
    private val counterHit by boolean("CounterHit", true)
    private val counterRange by float("CounterRange", 3.0f, 0.0f..6.0f, "blocks")
    private val counterCdMs by int("CounterCD", 300, 0..1000, "ms")

    // ========== State ==========
    /** Last time Rule 1 fired a counter-attack (cooldown). */
    private var lastCounterNano: Long = 0L
    /** Synthetic counter-attack pulse: set true for one background tick, then cleared. */
    private var counterPulse: Boolean = false

    // ========== StartTick Listener (background 20Hz — decision only, lands on main thread) ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onStartTick(p, t)
    }

    private fun onStartTick(player: PlayerState, target: TargetState?) {
        // Default for this tick: clicks pass through. Rules below may flip it off.
        EventBridge.attackAllowed = true
        // Clear a previous counter pulse (unless re-triggered below).
        if (counterPulse) {
            EventBridge.syntheticAttack = false
            counterPulse = false
        }

        val t = target

        // ---- Rule 2: Retiming — target still invulnerable → swallow the click ----
        if (retiming && t != null && t.distance >= 0f && t.distance <= retimeRange
            && t.hurtResistantTime > retimeAt) {
            EventBridge.attackAllowed = false
            return
        }

        // ---- Rule 1: CounterHit — in range, not clicking, just got hit → auto attack once ----
        if (counterHit && t != null && t.distance >= 0f && t.distance <= counterRange
            && !EventBridge.isLeftMousePhysicallyDown
            && player.hurtTime > 0
            && System.nanoTime() - lastCounterNano >= counterCdMs * 1_000_000L) {
            lastCounterNano = System.nanoTime()
            EventBridge.syntheticAttack = true
            counterPulse = true
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        lastCounterNano = 0L
        counterPulse = false
        EventBridge.syntheticAttack = false
        EventBridge.attackAllowed = true
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        EventBridge.syntheticAttack = false
        EventBridge.attackAllowed = true
        counterPulse = false
    }
}
