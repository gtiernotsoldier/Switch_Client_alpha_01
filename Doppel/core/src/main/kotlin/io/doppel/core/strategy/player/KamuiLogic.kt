package io.doppel.core.strategy.player

/**
 * Kamui's phase machine — the **oracle**.
 *
 * The live path is `strategy::kamui` in `core-rs`; `KamuiParityTest` replays
 * the golden fixture through this object to prove the two agree. Nothing in
 * the shipped agent refers to it — `:core` is not a dependency of any runtime
 * module.
 *
 * The JVM keeps only judgements and platform I/O: the player being dead, the
 * GUI being open (already folded with the FlushOnGui switch), the trigger key
 * edge surviving its hop from the key thread to the next tick, and the claim /
 * release / flush writes. Everything stateful lives here: the phase machine
 * (IDLE / PHASE / COOLDOWN), the tick counters, the cast counter and the knob
 * set latched at the moment the phase started.
 *
 * Ported branch for branch from the former JVM-side `KamuiEngine` — including
 * the **order** of the safety overrides (death beats GUI beats the cap) and
 * the retune rule (the knob set in force for a cast is fixed when the cast
 * starts; the original engine ignored config changes outside IDLE, so a live
 * cast was never retuned mid-flight). There is **no randomness** — no RNG
 * stream, the machine is a pure function of the inputs and the state.
 *
 * A tick that carries the edge in IDLE only *starts* the phase (state PHASE,
 * phaseTick 0, no flags) — the phase is evaluated on the next tick call,
 * exactly as `trigger()` + `tick()` did across the same boundary.
 */
object KamuiLogic {

    /** Machine states, as carried by the answer's low nibble on the wire. */
    const val STATE_IDLE = 0
    const val STATE_PHASE = 1
    const val STATE_COOLDOWN = 2

    /** Flag bits, in the answer's second nibble (bits 4-7). */
    const val FLAG_CLAIM = 1
    const val FLAG_RELEASE = 2

    data class State(
        /** 0 IDLE, 1 PHASE, 2 COOLDOWN. */
        val state: Int = STATE_IDLE,
        /** Ticks spent in the current phase; 0 on the tick that started it. */
        val phaseTick: Int = 0,
        /** Ticks spent in the current cooldown. */
        val cooldownTick: Int = 0,
        /** Completed phase starts, for logs and the HUD. */
        val casts: Int = 0,
        /** The knob set in force for the current cast, latched when the phase
         *  started — the original engine ignored config changes outside IDLE,
         *  so a cast was never retuned mid-flight. */
        val latchedPhaseTicks: Int = 0,
        val latchedMaxHoldTicks: Int = 0,
        val latchedCooldownTicks: Int = 0
    )

    /** The judgements and knobs the adapter marshals into one request per tick. */
    data class Input(
        val triggerEdge: Boolean,
        val guiOpen: Boolean,
        val dead: Boolean,
        val phaseTicks: Int,
        val maxHoldTicks: Int,
        val cooldownTicks: Int
    )

    /** The packed machine view, unpacked — what the fixture's expected columns carry. */
    data class Answer(
        val state: Int,
        /** CLAIM_HOLD this tick — refresh the vault claim. */
        val claim: Boolean,
        /** RELEASE_AND_FLUSH this tick — drop the claim and flush the backlog. */
        val release: Boolean,
        val phaseTick: Int,
        val cooldownTick: Int
    )

    data class Decision(
        val answer: Answer,
        /** The machine state after the tick — what the next call receives. */
        val state: State
    )

    fun tick(state: State, input: Input): Decision {
        var s = state

        return when (s.state) {
            STATE_IDLE -> {
                if (input.triggerEdge) {
                    s = s.copy(
                        state = STATE_PHASE,
                        phaseTick = 0,
                        cooldownTick = 0,
                        casts = s.casts + 1,
                        // Latch the knobs the whole cast (phase + cooldown) will
                        // run under. Retune mid-flight is ignored by construction.
                        latchedPhaseTicks = input.phaseTicks,
                        latchedMaxHoldTicks = input.maxHoldTicks,
                        latchedCooldownTicks = input.cooldownTicks
                    )
                    // The started phase is evaluated on the NEXT tick call,
                    // exactly like the original where trigger() happened between
                    // ticks. No flags: the JVM claims at the keypress itself.
                    Decision(Answer(s.state, false, false, s.phaseTick, s.cooldownTick), s)
                } else {
                    // IDLE: nothing this tick, no flags.
                    Decision(Answer(s.state, false, false, s.phaseTick, s.cooldownTick), s)
                }
            }

            STATE_PHASE -> {
                s = s.copy(phaseTick = s.phaseTick + 1)
                // Safety overrides, in the order they are allowed to fire. A phase is never
                // worth a GUI stall (the player cannot play blink while a screen eats their
                // input) and never worth a runaway hold (the hard cap bounds a mistuned
                // Phase option no matter what).
                when {
                    input.dead ->
                        // Hard reset: no cooldown, a dead player owes none.
                        s = s.copy(state = STATE_IDLE, phaseTick = 0, cooldownTick = 0)
                    input.guiOpen -> s = s.copy(state = STATE_COOLDOWN, cooldownTick = 0)
                    s.phaseTick >= minOf(s.latchedPhaseTicks, s.latchedMaxHoldTicks) ->
                        s = s.copy(state = STATE_COOLDOWN, cooldownTick = 0)
                    else -> return Decision(
                        Answer(s.state, claim = true, release = false, s.phaseTick, s.cooldownTick),
                        s
                    )
                }
                Decision(Answer(s.state, claim = false, release = true, s.phaseTick, s.cooldownTick), s)
            }

            else -> {
                // COOLDOWN. No flags — the claim was dropped at the release; a
                // cooldown tick never holds anything.
                s = s.copy(cooldownTick = s.cooldownTick + 1)
                if (s.cooldownTick >= s.latchedCooldownTicks) {
                    s = s.copy(state = STATE_IDLE, cooldownTick = 0)
                }
                Decision(Answer(s.state, false, false, s.phaseTick, s.cooldownTick), s)
            }
        }
    }
}
