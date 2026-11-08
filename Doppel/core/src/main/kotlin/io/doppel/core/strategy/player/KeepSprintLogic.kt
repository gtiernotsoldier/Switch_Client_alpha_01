package io.doppel.core.strategy.player

/**
 * KeepSprint's hold machine — the **oracle**.
 *
 * The live path is `strategy::keep_sprint` in `core-rs`;
 * `KeepSprintParityTest` replays the golden fixture through this object to
 * prove the two agree. Nothing in the shipped agent refers to it — `:core` is
 * not a dependency of any runtime module.
 *
 * The JVM keeps only judgements and platform I/O: the attack composite
 * (physical button OR a clicker's synthetic attack), the crosshair target
 * being alive, the forward-key arbitration, the tap-owns-W override, and
 * the sprint-claim / C0B writes. Everything stateful lives here: the
 * sprint-sample latch, the two-dispatch hold window, the wall-clock
 * backstop and the per-tick assert dedup.
 *
 * Mirrors the original `KeepSprint.onRenderFrame`:
 *  - only a "sprinting" sample refreshes the latch;
 *  - the hold ends by the dispatch window, the wall clock, or the player
 *    letting go of forward — evaluated before the attack gates so an
 *    expired hold ends even on a frame that asserts nothing;
 *  - detection is per tick (a held button attacks every tick in 1.8), not
 *    per edge and not per frame;
 *  - a swing at air or a dead target asserts nothing and consumes no tick;
 *  - a sprint is preserved, never created (sample or latch within 120 ms);
 *  - an assert re-arms the hold after any release the same frame produced.
 *
 * There is no randomness — no roll parameter, no RNG stream — so the
 * answer is a pure function of the frame's inputs and the state. The
 * original re-read `System.nanoTime()` when arming the hold deadline; the
 * port uses the frame's `nowNs` for both, a sub-millisecond difference on
 * a 150 ms backstop.
 */
object KeepSprintLogic {

    /** Wall-clock backstop for the hold, in nanoseconds. */
    const val HOLD_MAX_NANOS = 150_000_000L

    /** How long a "sprinting" sample stays credible, in nanoseconds. */
    const val SPRINT_MEMORY_NANOS = 120_000_000L

    data class State(
        /** Dispatch tick at which the current hold stops being applied; -1 when no hold is live. */
        val holdUntilTick: Int = -1,
        /** Wall-clock deadline of the hold; 0 when no hold is live. */
        val holdDeadlineNs: Long = 0L,
        /** Last dispatch tick an attack was handled on. */
        val lastHandledTick: Int = -1,
        /** Last time the sprint sample reported "sprinting"; 0 when never. */
        val lastSprintSeenNs: Long = 0L
    )

    /** The hold half of the answer. A status, not just a transition. */
    enum class Hold(val code: Int) {
        /** No hold is live — the adapter releases any claim it still holds. */
        DEAD(0),

        /** A hold is live; keep claiming. */
        LIVE(1),

        /** The hold ended on this frame — release now. */
        RELEASE(2)
    }

    /** The assert half of the answer. Codes match the wire. */
    enum class Assert(val code: Int) { NONE(0), ASSERT(1) }

    data class Input(
        val nowNs: Long,
        val tick: Int,
        val localSprinting: Boolean,
        val attacking: Boolean,
        val targetAlive: Boolean,
        val forward: Boolean,
        val forwardOverride: Boolean,
        /** The module's `OnlySprint`: require a live sprint sample, no memory window. */
        val requireSprinting: Boolean = false
    )

    data class Decision(val code: Int, val state: State)

    fun pack(hold: Hold, assert: Assert): Int = hold.code or (assert.code shl 4)

    fun unpackHold(code: Int): Int = code and 0xF

    fun unpackAssert(code: Int): Int = (code shr 4) and 0xF

    fun frame(state: State, input: Input): Decision {
        var s = state

        // ---- Sprint-sample latch ----
        if (input.localSprinting) {
            s = s.copy(lastSprintSeenNs = input.nowNs)
        }

        // ---- Hold upkeep ----
        var hold = Hold.DEAD
        if (s.holdUntilTick >= 0) {
            if (input.tick > s.holdUntilTick ||
                input.nowNs > s.holdDeadlineNs ||
                !input.forward
            ) {
                s = s.copy(holdUntilTick = -1, holdDeadlineNs = 0L)
                hold = Hold.RELEASE
            } else {
                hold = Hold.LIVE
            }
        }

        // ---- Assert evaluation ----
        var assert = Assert.NONE
        if (input.attacking && input.tick != s.lastHandledTick && input.targetAlive) {
            val sprintWasOn = if (input.requireSprinting) {
                input.localSprinting
            } else {
                input.localSprinting ||
                    (s.lastSprintSeenNs != 0L && input.nowNs - s.lastSprintSeenNs <= SPRINT_MEMORY_NANOS)
            }
            if (sprintWasOn && input.forward && !input.forwardOverride) {
                s = s.copy(
                    lastHandledTick = input.tick,
                    holdUntilTick = input.tick + 1,
                    holdDeadlineNs = input.nowNs + HOLD_MAX_NANOS
                )
                assert = Assert.ASSERT
                hold = Hold.LIVE
            }
        }

        return Decision(pack(hold, assert), s)
    }
}
