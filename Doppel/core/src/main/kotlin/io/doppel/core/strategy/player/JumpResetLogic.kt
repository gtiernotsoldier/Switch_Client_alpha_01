package io.doppel.core.strategy.player

/**
 * JumpReset's knock / pump decisions — the **oracle**.
 *
 * The live path is `strategy::jump_reset` in `core-rs`; `JumpResetParityTest`
 * replays the golden fixture through this object to prove the two agree.
 * Nothing in the shipped agent refers to it — `:core` is not a dependency of
 * any runtime module.
 *
 * The JVM keeps only judgements and platform I/O: the internal conditions
 * (sprinting / on ground / not in fluid), the front-angle geometry check,
 * the unified gate, and the `queueJump` write. Everything stateful lives
 * here: the cooldown counter (two modes), the pending delayed jump and its
 * deadline, and the probability roll — on JumpReset's own RNG stream.
 *
 * Two entry points share one [State], mirroring the two JVM threads:
 *  - [knock] runs on the velocity (Netty) thread when an S12/S27 arrives;
 *  - [pump] runs once per client tick to advance the Ticks cooldown and fire
 *    a delayed jump at its deadline.
 *
 * The roll closure is called **only** in [knock], after the gates and only
 * when `chance < 100`. [pump] never draws — a port that rolls on a tick would
 * advance the stream with frame timing and diverge on the first replay.
 */
object JumpResetLogic {

    /** What the core remembers between a knockback and the jump it may cause. */
    data class State(
        /** Ticks mode: ticks since the last jump; ReceivedHits: knockbacks since. */
        val cooldownCounter: Int = 0,
        /** A jump is armed and waiting for its delay deadline. */
        val pending: Boolean = false,
        /** Nanosecond deadline at which the armed jump fires. */
        val deadlineNs: Long = 0L
    )

    /** 0 = Ticks (cooldown advances per tick), 1 = ReceivedHits (per knockback). */
    enum class CooldownMode {
        TICKS, RECEIVED_HITS;

        companion object {
            fun fromCode(c: Int): CooldownMode = if (c == 1) RECEIVED_HITS else TICKS
        }
    }

    /** The judgements and knobs the velocity thread marshals into one request. */
    data class KnockInput(
        /** All internal conditions + the unified gate, combined into one bool. */
        val gatesOpen: Boolean,
        val nowNs: Long,
        /** Fire probability, 0..100. 100 disables the roll entirely. */
        val chance: Int,
        val cooldownMode: CooldownMode,
        val ticksUntil: Int,
        val hitsUntil: Int,
        /** Delay the jump by this many ms (0 = immediate). */
        val delayMs: Int
    )

    /** Per-tick inputs. No roll, no gates — this half only advances the clock. */
    data class PumpInput(
        val nowNs: Long,
        val cooldownMode: CooldownMode,
        val ticksUntil: Int
    )

    /** The numeric code is the wire value. */
    enum class Step(val code: Int) {
        /** Gates closed, chance rejected, cooldown not met, or a jump was armed. */
        NONE(0),
        /** Execute the jump now. */
        JUMP(1)
    }

    data class Decision(val step: Step, val state: State)

    /**
     * A knockback arrived. Decide whether to jump now, arm a delayed jump, or
     * do nothing.
     *
     * @param roll invoked **only** after the gates and only when `chance < 100`,
     *        so closed / throttled / cooldown-blocked knocks never advance the
     *        caller's randomness.
     */
    fun knock(state: State, input: KnockInput, roll: () -> Int): Decision {
        var s = state

        // Gates closed: no roll, no cooldown change. This preserves the ordering
        // of the original onKnockback, which returned before touching any state.
        if (!input.gatesOpen) return Decision(Step.NONE, s)

        // The only draw. Kotlin's `Random.nextInt(100)` is [0, 100); a roll >=
        // chance rejects when chance < 100. chance == 100 never draws.
        if (input.chance < 100 && roll() >= input.chance) return Decision(Step.NONE, s)

        when (input.cooldownMode) {
            CooldownMode.TICKS -> {
                if (s.cooldownCounter < input.ticksUntil) return Decision(Step.NONE, s)
            }
            CooldownMode.RECEIVED_HITS -> {
                s = s.copy(cooldownCounter = s.cooldownCounter + 1)
                if (s.cooldownCounter < input.hitsUntil) return Decision(Step.NONE, s)
            }
        }

        // Firing: reset the cooldown, then either schedule or emit immediately.
        s = s.copy(cooldownCounter = 0)
        return if (input.delayMs > 0) {
            s = s.copy(pending = true, deadlineNs = input.nowNs + input.delayMs * 1_000_000L)
            Decision(Step.NONE, s)
        } else {
            Decision(Step.JUMP, s)
        }
    }

    /**
     * One client tick: advance the Ticks cooldown, then fire a delayed jump
     * that reached its deadline. The two are independent in the original tick
     * listener (two separate `if`s), so both happen even on the firing tick.
     */
    fun pump(state: State, input: PumpInput): Decision {
        var s = state

        if (input.cooldownMode == CooldownMode.TICKS && s.cooldownCounter < input.ticksUntil) {
            s = s.copy(cooldownCounter = s.cooldownCounter + 1)
        }

        if (s.pending && input.nowNs >= s.deadlineNs) {
            s = s.copy(pending = false)
            return Decision(Step.JUMP, s)
        }

        return Decision(Step.NONE, s)
    }
}
