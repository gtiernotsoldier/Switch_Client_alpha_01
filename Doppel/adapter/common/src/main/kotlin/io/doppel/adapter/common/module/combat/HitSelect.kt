package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int

/**
 * HitSelect — click selector. Decides whether an attack click is worth landing; a click that is
 * not worth landing is swallowed (the attack key is held up for that frame), so the hit is not
 * wasted and is not sent to the server.
 *
 * Rewritten against the two Raven designs (see the reference notes in the commit):
 *  - raven-bs: burst/criticals selector that cancels `clickMouse`; blocks valid hits for a pause
 *    window after your own hit, waits for the first hit on a new target, and classifies clicks as
 *    block-interaction / missed swing / valid hit (block interaction is NEVER swallowed).
 *  - Raven XD: "Pause/Active" gate with a preference (move speed / knockback reduction / criticals),
 *    a delay and a chance, consumed by AutoClicker and KillAura.
 *
 * Rules, all evaluated on the MAIN THREAD at the click frame (the platform calls the gate from
 * applySyntheticInput, so the decision is made when the click happens, not on the 20Hz tick):
 *
 *   1. A screen is open, or the crosshair is on a block: always pass. Mining and placing must not
 *      be disturbed, and block interaction is not an attack.
 *   2. [Criticals] on: only pass while a critical hit is possible (vanilla: airborne and falling).
 *   3. The target is still invulnerable ([RetimeAt]): hold. Landing a hit inside the i-frame window
 *      does nothing on the server, which is the waste this module exists to avoid.
 *   4. [Pause] ms after we confirmed a hit on the target: hold. This is the burst window — it lets
 *      the target's invulnerability run out before the next click instead of mashing into it.
 *   5. [WaitFirst] ms after a newly acquired target: hold, so the opening hit is not thrown away
 *      while the aim is still settling.
 *   6. [Chance] gates how often the selector applies at all (100 = always).
 *   7. [MissedSwing] gates clicks that hit nothing (raven-bs's "Missed swings" cancel rate).
 *
 * A hit is "confirmed" when the target's hurtTime rises between two ticks — the server-confirmed
 * signal, same as raven-bs's server attack time. Predicting it from our own clicks was the old
 * design's weakness: the gate is consulted every frame, so it cannot tell a click from an idle
 * frame without a real signal.
 *
 * [CounterHit] is kept from the previous implementation: when we take a hit while not clicking,
 * fire one automatic counter-attack (cooldown-gated).
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Burst window ==========
    /** Hold clicks for this long after a confirmed hit on the target (raven-bs "Pause duration"). */
    private val pauseMs by int("Pause", 500, 0..1000, "ms")

    /** Also hold while the target's hurtResistantTime is above this (the i-frame gate). */
    private val retimeAt by int("RetimeAt", 3, 0..10, "ticks")

    /** Hold clicks for this long after acquiring a new target (raven-bs "Wait for first hit"). */
    private val waitFirstMs by int("WaitFirst", 0, 0..1000, "ms")

    // ========== Preferences ==========
    /** Only land clicks that can crit (airborne + falling). */
    private val criticals by boolean("Criticals", false)

    /** Probability that the selector applies to a click at all (lower = more clicks pass). */
    private val chance by int("Chance", 100, 0..100, "%")

    /**
     * Swallow clicks that hit nothing (no entity on the forward ray and no block) with this
     * probability — raven-bs's "Missed swings" cancel rate. Off by default: a whiffed swing is
     * normal and cancelling it entirely is a tell of its own.
     */
    private val missedSwing by int("MissedSwing", 0, 0..100, "%")

    // ========== CounterHit (eat a hit → hit back once) ==========
    private val counterHit by boolean("CounterHit", false)
    private val counterCdMs by int("CounterCD", 300, 0..1000, "ms")

    // ========== State ==========
    /** nanoTime of the last confirmed hit on the current target (burst window). */
    @Volatile private var lastConfirmedHitNano: Long = 0L
    /** nanoTime when the current target was first seen (WaitFirst window). */
    @Volatile private var targetFirstSeenNano: Long = 0L
    /** Id of the target the windows belong to; a change restarts them. */
    @Volatile private var trackedTargetId: Int = -1
    /** Previous tick's target hurtTime, for the server-confirmed hit edge. */
    @Volatile private var prevTargetHurtTime: Int = 0
    /** Whether a critical hit is possible right now, refreshed on the tick. */
    @Volatile private var canCritNow: Boolean = false

    /** Last time CounterHit fired. */
    private var lastCounterNano: Long = 0L
    /** Synthetic counter-attack pulse: set for one tick, then cleared. */
    private var counterPulse: Boolean = false
    /** Shared RNG (the gate runs on the render thread, the tick listener on the tick thread). */
    private val rng = java.util.concurrent.ThreadLocalRandom.current()

    // ========== Main-thread attack gate ==========
    private val gate: () -> Boolean = {
        if (!enabled) true else evaluateGate()
    }

    private fun evaluateGate(): Boolean {
        // 1. Never swallow clicks while a screen is open or the crosshair is on a block: block
        //    interaction (mining, placing) is not an attack and must keep working.
        if (EventBridge.isGuiOpen) return true
        if (EventBridge.isLookingAtBlock) return true

        // 6. Chance: skip the selector entirely for this click.
        if (chance < 100 && rng.nextInt(100) >= chance) return true

        // 2. Criticals only.
        if (criticals && !canCritNow) return false

        val target = EventBridge.getForwardRayTarget()
        if (target == null) {
            // Nothing on the forward ray: this click would only animate. raven-bs scores these as
            // missed swings and can cancel them by a separate rate (default off here).
            return !(missedSwing > 0 && rng.nextInt(100) < missedSwing)
        }

        // 5. Wait for the first hit on a newly acquired target.
        if (waitFirstMs > 0 && System.nanoTime() - targetFirstSeenNano < waitFirstMs * 1_000_000L) return false

        // 3. Still invulnerable: the hit would be discarded by the server.
        if (target.hurtResistantTime > retimeAt) return false

        // 4. Burst window after our own confirmed hit.
        if (pauseMs > 0 && System.nanoTime() - lastConfirmedHitNano < pauseMs * 1_000_000L) return false

        return true
    }

    // ========== Tick listener (target tracking + CounterHit) ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onStartTick(p, t)
    }

    private fun onStartTick(player: PlayerState, target: TargetState?) {
        // Refresh the crit test from real player state (vanilla canCriticalHit, approximated: the
        // game also requires not in water / on a ladder / blindness, none of which are in
        // PlayerState today).
        canCritNow = player.motionY < 0.0 && !player.onGround

        if (target == null) {
            trackedTargetId = -1
            prevTargetHurtTime = 0
            counterPulse = false
            EventBridge.syntheticAttack = false
            return
        }

        val now = System.nanoTime()
        if (target.id != trackedTargetId) {
            // New target: restart both windows and do not inherit the previous target's hurt state.
            trackedTargetId = target.id
            targetFirstSeenNano = now
            lastConfirmedHitNano = 0L
            prevTargetHurtTime = target.hurtTime
        }
        // Server-confirmed hit: the target's hurtTime rose since the previous tick.
        if (target.hurtTime > prevTargetHurtTime) lastConfirmedHitNano = now
        prevTargetHurtTime = target.hurtTime

        // CounterHit: eat a hit while not clicking → answer it once.
        if (counterPulse) {
            EventBridge.syntheticAttack = false
            counterPulse = false
        }
        if (counterHit && !EventBridge.isLeftMousePhysicallyDown && player.hurtTime > 0 &&
            now - lastCounterNano >= counterCdMs * 1_000_000L
        ) {
            lastCounterNano = now
            EventBridge.syntheticAttack = true
            counterPulse = true
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        lastConfirmedHitNano = 0L
        targetFirstSeenNano = 0L
        trackedTargetId = -1
        prevTargetHurtTime = 0
        canCritNow = false
        lastCounterNano = 0L
        counterPulse = false
        EventBridge.registerAttackGateProvider(gate)
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        EventBridge.registerAttackGateProvider(null)
        EventBridge.syntheticAttack = false
        counterPulse = false
        trackedTargetId = -1
        lastConfirmedHitNano = 0L
    }
}
