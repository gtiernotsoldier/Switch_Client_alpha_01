package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.int
import io.doppel.ipc.Strategies

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
 * The guard chain, the three time windows and both rolls now live in the core. This module is the
 * only one whose decision is split across two threads, so it sends two kinds of request:
 *  - on the 20Hz tick it reports the target view and refreshes the windows;
 *  - at the click it packs the predicates and asks whether to deliver it.
 * Both mutate one state in the core. A hit is "confirmed" when the target's hurtTime rises between
 * two ticks — the server-confirmed signal, same as raven-bs's server attack time.
 *
 * A missing answer on the gate means **deliver** the click. Swallowing a real hit without being
 * told to is worse than letting one through, so the safe direction here is the opposite of Reach's.
 *
 * The one-tick synthetic-attack pulse for [counterHit] is a platform concern and stays here: the
 * core says *whether* to fire, this side holds the key for one tick and releases it on the next.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    init {
        // COMBAT modules are silent on the HUD by default, and this one especially needs the indicator:
        // its whole job is invisible — a swallowed click looks exactly like a click that did nothing.
        showRedIndicator = true
    }

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

    /** Probability that the selector applies to a click at all (100 = always). */
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
    /**
     * Last tick's player snapshot.
     *
     * The gate fires on the render thread, where no state object is handed in;
     * a tick-old snapshot is enough to marshal the request, and the core does
     * not read the player for either op.
     */
    @Volatile private var latestPlayer: PlayerState? = null

    /** Set for one tick while a synthetic counter-attack is held down. */
    private var counterPulse: Boolean = false

    // ========== Main-thread attack gate ==========
    private val gate: () -> Boolean = {
        if (!enabled) {
            true
        } else {
            // Every swallowed click is this module working, and that is what the HUD pulse shows. It is
            // pulsed from here rather than from the tick because the decision — and therefore the work —
            // happens at the click, on the render thread.
            val pass = evaluateGate()
            if (!pass) markWorked()
            pass
        }
    }

    private fun evaluateGate(): Boolean {
        // No snapshot yet: deliver rather than swallow. Adding a click gate on
        // a press the core never saw is the one thing this module must not do.
        val player = latestPlayer ?: return true
        val forward = EventBridge.getForwardRayTarget()
        return Strategies.hitGate(
            player,
            forward,
            System.nanoTime(),
            EventBridge.isGuiOpen,
            EventBridge.isLookingAtBlock,
            chance,
            criticals,
            missedSwing,
            forward != null,
            forward?.hurtResistantTime ?: 0,
            waitFirstMs,
            retimeAt,
            pauseMs
        ) ?: true // core unreachable → deliver
    }

    // ========== Tick listener (state feed + CounterHit pulse) ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        latestPlayer = p
        if (enabled) onStartTick(p, t)
    }

    private fun onStartTick(player: PlayerState, target: TargetState?) {
        // Release last tick's pulse before asking for a new one, exactly as the
        // in-process version did.
        if (counterPulse) {
            EventBridge.syntheticAttack = false
            counterPulse = false
        }
        if (target == null) {
            EventBridge.syntheticAttack = false
        }

        val fire = Strategies.hitTick(
            player,
            target,
            System.nanoTime(),
            canCrit = player.motionY < 0.0 && !player.onGround,
            playerHurtTime = player.hurtTime,
            leftMouseDown = EventBridge.isLeftMousePhysicallyDown,
            hasTarget = target != null,
            targetId = target?.id ?: -1,
            targetHurtTime = target?.hurtTime ?: 0,
            counterHit = counterHit,
            counterCdMs = counterCdMs
        ) ?: return // no answer → no counter-attack this tick

        if (fire) {
            EventBridge.syntheticAttack = true
            markWorked() // the HUD row flashes while it is counter-hitting
            counterPulse = true
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        latestPlayer = null
        counterPulse = false
        // Windows the core still remembers belong to the previous activation.
        Strategies.resetPlayer()
        EventBridge.registerAttackGateProvider(gate)
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        EventBridge.registerAttackGateProvider(null)
        EventBridge.syntheticAttack = false
        counterPulse = false
        latestPlayer = null
        Strategies.resetPlayer()
    }
}
