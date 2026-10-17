package io.doppel.core.strategy.combat

/**
 * AutoBlock's Adaptive mode — one decision out of several signals.
 *
 * The module's three original modes turn out to be the *same question asked with different
 * information*, which is what makes combining them meaningful rather than arbitrary:
 *
 *   Normal  — "am I attacking right now?"              → one boolean, a very short hold
 *   Sag     — "have I been attacking continuously?"    → the same boolean, a longer view
 *   Predict — "can my hit land / is one coming back?"  → the target's state and the opponent's rhythm
 *
 * Normal and Sag watch the same trigger and differ only in how long they hold; Predict watches
 * something else entirely. So a combination cannot be "pick one" — it is a weighting over four
 * signals, two of which come from Predict and two from the attacker's own rhythm.
 *
 * ── Why the weights are what they are
 *
 * They track **how much each signal actually knows**, and that is the whole design:
 *
 *   lock    (1.0) — the target is inside its damage lock. Arithmetic, not a guess: our hit cannot
 *                   land and the target can still hit us, so holding is free value.
 *   threat  (0.6) — an estimated attack is due. A real signal, but an estimate from a clock whose
 *                   defaults are placeholders.
 *   sustain (0.5) — we have been attacking. True almost all the time during a fight, which is
 *                   exactly why it cannot be worth much alone: a signal that is always on carries
 *                   little information.
 *   reflex  (0.4) — we just swung. The same objection, stronger.
 *
 * Against a threshold of 0.5 this gives a deliberate structure: **lock, threat and sustain each
 * hold alone — so Adaptive is never weaker than the single mode it replaces — while reflex, the
 * least informative of the four, only holds in company.** The point is the shape rather than the
 * arithmetic: an OR over all four would block essentially always, and "always blocking" is the one
 * thing a server can read off a player instantly.
 *
 * ── The duty gate
 *
 * The other half of the evaluation is *how much of the time* the guard is up. Duty cycle is the
 * observable quantity here — it is what a HUD shows and what human blocking looks like — so the
 * arbiter can be given a budget: above `maxDuty` only the deterministic signal may keep holding,
 * because "I cannot damage them anyway" is not behaviour that needs rationing.
 *
 * Pure Core: no Minecraft, no EventBridge, no input.
 */
object BlockArbiter {

    /** One signal's opinion for this tick. */
    data class Signal(val name: String, val block: Boolean, val weight: Double)

    data class Weights(
        val lock: Double = DEFAULT_LOCK,
        val threat: Double = DEFAULT_THREAT,
        val sustain: Double = DEFAULT_SUSTAIN,
        val reflex: Double = DEFAULT_REFLEX
    )

    data class Verdict(
        val block: Boolean,
        /** Sum of the firing signals' weights — what the threshold was compared against. */
        val score: Double,
        /** Names of the signals that were on, for the HUD and for tests. */
        val firing: List<String>,
        /** True when the duty gate overrode an otherwise-passing decision. */
        val throttled: Boolean
    )

    const val DEFAULT_LOCK = 1.0
    const val DEFAULT_THREAT = 0.6
    const val DEFAULT_SUSTAIN = 0.5
    const val DEFAULT_REFLEX = 0.4
    const val DEFAULT_THRESHOLD = 0.5

    /** Default observation window for the duty cycle, in ticks (60 = 3 s). */
    const val DEFAULT_DUTY_WINDOW = 60

    /**
     * Rolling duty-cycle tracker: what fraction of the last [windowTicks] ticks the guard was up.
     *
     * Kept here rather than in the module so the thing being measured and the thing measuring it
     * stay in the same layer.
     */
    class State(private val windowTicks: Int = DEFAULT_DUTY_WINDOW) {
        private val held = BooleanArray(windowTicks)
        private var cursor = 0
        private var filled = 0

        /** Fraction of the observed window the guard was up, 0..1. */
        fun duty(): Double = if (filled == 0) 0.0 else held.count { it }.toDouble() / filled

        fun record(blocked: Boolean) {
            held[cursor] = blocked
            cursor = (cursor + 1) % windowTicks
            if (filled < windowTicks) filled++
        }

        fun reset() {
            held.fill(false)
            cursor = 0
            filled = 0
        }
    }

    /**
     * Combine the signals into one verdict.
     *
     * @param maxDuty budget for the guard's duty cycle, 0..1. At or above it, only [locked] may
     *   still hold. 1.0 disables the gate (the comparison is strictly greater).
     */
    fun evaluate(
        locked: Boolean,
        threat: Boolean,
        sustain: Boolean,
        reflex: Boolean,
        weights: Weights,
        threshold: Double,
        duty: Double = 0.0,
        maxDuty: Double = 1.0
    ): Verdict {
        val signals = listOf(
            Signal("lock", locked, weights.lock),
            Signal("threat", threat, weights.threat),
            Signal("sustain", sustain, weights.sustain),
            Signal("reflex", reflex, weights.reflex)
        )
        val firing = signals.filter { it.block }
        val score = firing.sumOf { it.weight }
        val names = firing.map { it.name }

        if (score < threshold) {
            return Verdict(block = false, score = score, firing = names, throttled = false)
        }

        // Over budget: only the deterministic signal may keep the guard up.
        if (duty > maxDuty && !locked) {
            return Verdict(block = false, score = score, firing = names, throttled = true)
        }

        return Verdict(block = true, score = score, firing = names, throttled = false)
    }
}
