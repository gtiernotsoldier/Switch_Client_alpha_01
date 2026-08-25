package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.int

/**
 * HitSelect - a click selector for the CROSSHAIR target only (no distance filter - the crosshair
 * being on the entity is enough; a distance check was unreliable in fights and is deliberately
 * omitted).
 *
 * Works through two cross-thread flags that ForgeEventBridge.applySyntheticInput applies on the
 * MAIN thread (the only place the real attack key is written):
 *   - attackAllowed=false forces the attack key OFF (swallows clicks / AutoClicker hits) even
 *     while the mouse is held. AutoClicker/TriggerBot's full-clicker override also honours it.
 *   - syntheticAttack=true is a one-tick pulse that fires one counter-attack.
 *
 * A Mode selector picks which rule is active:
 *   - Retiming: while the crosshair target's hurtResistantTime > RetimeAt it is still invulnerable,
 *     so clicks are swallowed; when it drops to RetimeAt the click passes through - the re-timed
 *     hit lands exactly as the target becomes hittable again.
 *   - CounterHit: when not clicking and just got hit (hurtTime>0), fire one automatic counter-attack
 *     once (cooldown-gated).
 *   - Both: both rules run.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Mode selector (which rule is active) ==========
    private val mode by choices("Mode", arrayOf("Both", "Retiming", "CounterHit"))

    // ========== Retiming (swallow clicks inside the crosshair target's i-frame) ==========
    /** Target is considered "about to become hittable" when hurtResistantTime <= this. */
    private val retimeAt by int("RetimeAt", 3, 0..10, "ticks")

    // ========== CounterHit (eat a hit -> auto hit back) ==========
    private val counterCdMs by int("CounterCD", 300, 0..1000, "ms")

    // ========== State ==========
    /** Last time CounterHit fired a counter-attack (cooldown). */
    private var lastCounterNano: Long = 0L
    /** Synthetic counter-attack pulse: set true for one background tick, then cleared. */
    private var counterPulse: Boolean = false

    // ========== StartTick Listener (background 20Hz - decision only, lands on main thread) ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onStartTick(p)
    }

    private fun onStartTick(player: PlayerState) {
        // Default for this tick: clicks pass through. Rules below may flip it off.
        EventBridge.attackAllowed = true
        // Clear a previous counter pulse (unless re-triggered below).
        if (counterPulse) {
            EventBridge.syntheticAttack = false
            counterPulse = false
        }

        // Only the CROSSHAIR target matters (no nearest-entity fallback, no distance check).
        val t = EventBridge.crosshairTarget
        if (t == null) return

        // ---- Retiming: crosshair target still invulnerable -> swallow the click ----
        if ((mode == "Both" || mode == "Retiming") && t.hurtResistantTime > retimeAt) {
            EventBridge.attackAllowed = false
            return
        }

        // ---- CounterHit: not clicking, just got hit -> auto attack once ----
        if ((mode == "Both" || mode == "CounterHit")
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