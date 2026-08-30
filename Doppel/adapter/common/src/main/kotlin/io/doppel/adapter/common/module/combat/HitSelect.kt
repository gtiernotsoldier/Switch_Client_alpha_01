package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int

/**
 * HitSelect — a click selector for the entity on the player's FORWARD ray (the same technique
 * Reach/JumpReset use; not the unreliable objectMouseOver).
 *
 * Two rules:
 *   - Retiming: decided on the MAIN thread at the exact click frame. If the forward-ray target's
 *     hurtResistantTime > RetimeAt it is still invulnerable, so the click is swallowed; when it
 *     drops to RetimeAt the click passes through — the hit lands as the target becomes hittable.
 *     Implemented as an attack-gate provider the platform calls from applySyntheticInput every
 *     frame, so the decision is made when the click happens, not on the 20Hz background tick.
 *   - CounterHit: on the background tick, when not clicking and just got hit (hurtTime>0), fire one
 *     automatic counter-attack once (cooldown-gated) via a syntheticAttack pulse.
 *
 * A Mode selector picks which rule is active: Both / Retiming / CounterHit.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Mode selector (which rule is active) ==========
    private val mode by choices("Mode", arrayOf("Both", "Retiming", "CounterHit"))

    // ========== Retiming (swallow clicks inside the forward-ray target's i-frame) ==========
    /** Target is considered "about to become hittable" when hurtResistantTime <= this. */
    private val retimeAt by int("RetimeAt", 3, 0..10, "ticks")

    // ========== CounterHit (eat a hit -> auto hit back) ==========
    private val counterCdMs by int("CounterCD", 300, 0..1000, "ms")

    // ========== State ==========
    /** Last time CounterHit fired a counter-attack (cooldown). */
    private var lastCounterNano: Long = 0L
    /** Synthetic counter-attack pulse: set true for one background tick, then cleared. */
    private var counterPulse: Boolean = false

    // ========== Main-thread attack gate (Retiming — decided per click frame) ==========
    // The platform calls this from applySyntheticInput on the render thread right before it writes
    // the attack key, so a swallowed click is decided at the instant of the click. Returns true =
    // let the click through, false = swallow it (forced pause, then resumes next frame).
    private val gate: () -> Boolean = {
        if (enabled && (mode == "Both" || mode == "Retiming")) {
            val t = EventBridge.getForwardRayTarget()
            !(t != null && t.hurtResistantTime > retimeAt)
        } else {
            true
        }
    }

    // ========== Background listener (CounterHit only) ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onStartTick(p)
    }

    private fun onStartTick(player: PlayerState) {
        // Clear a previous counter pulse (unless re-triggered below).
        if (counterPulse) {
            EventBridge.syntheticAttack = false
            counterPulse = false
        }
        if (mode != "Both" && mode != "CounterHit") return

        val t = EventBridge.getForwardRayTarget()
        if (t == null) return
        if (!EventBridge.isLeftMousePhysicallyDown
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
        EventBridge.registerAttackGateProvider(gate)
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        EventBridge.registerAttackGateProvider(null)
        EventBridge.syntheticAttack = false
        counterPulse = false
    }
}
