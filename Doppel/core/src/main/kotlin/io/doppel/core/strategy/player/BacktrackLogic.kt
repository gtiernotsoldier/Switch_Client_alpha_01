package io.doppel.core.strategy.player

/**
 * Backtrack's hold window — the Kotlin **oracle** for `strategy::backtrack`.
 *
 * The live path is the Rust port; `BacktrackParityTest` replays the golden
 * fixture through this class. Nothing shipped refers to it.
 *
 * Only position updates are held, which is a deviation from the reference
 * (LiquidBounce cancels every received packet) and the reason this module is
 * safe to leave on: chat, sounds, health and the world stay live.
 *
 * `holdStartNs` uses -1 as "not holding", not 0. The caller's clock is allowed
 * to read 0 in a replay, and a 0 sentinel would then look like a hold that
 * started at the epoch.
 */
object BacktrackLogic {

    class State(
        var holdStartNs: Long = -1L,
        var pauseUntilNs: Long = -1L,
        var windowNs: Long = 150_000_000L
    )

    class Input(
        val nowNs: Long,
        /** Modern rolls a window per hold; Legacy uses a fixed 250 ms. */
        val modern: Boolean,
        /** Pulse holds once per window then pauses for the same length. */
        val pulse: Boolean,
        /** Modern: only hold when the target is close. Ignored in Legacy. */
        val smart: Boolean,
        val minDelayMs: Int,
        val maxDelayMs: Int,
        /** Distance to the target, or a negative value when there is none. */
        val distance: Float,
        /** Legacy mode's threshold; Modern ignores it. */
        val legacyDistance: Float,
        /** GUI open, dead, or being hit — the reference's "do not delay" list. */
        val mustRelease: Boolean
    )

    fun tick(state: State, input: Input, roll: (lo: Int, hi: Int) -> Int): Boolean {
        val hasTarget = input.distance >= 0f
        val inRange = if (input.modern) {
            hasTarget && (!input.smart || input.distance <= 6f)
        } else {
            hasTarget && input.distance <= input.legacyDistance
        }

        if (input.mustRelease || !inRange) {
            state.holdStartNs = -1L
            return false
        }

        // Pulse: after a window closes, stay quiet for the same length before the
        // next hold, so the pattern is hold-pause-hold rather than a solid block.
        if (input.modern && input.pulse && input.nowNs < state.pauseUntilNs) {
            state.holdStartNs = -1L
            return false
        }

        if (state.holdStartNs < 0) {
            state.holdStartNs = input.nowNs
            if (input.modern) {
                // A fresh window every time: an evenly spaced hold is the one thing
                // a real connection never produces, and it is what makes a pattern
                // detectable.
                val lo = minOf(input.minDelayMs, input.maxDelayMs)
                val hi = maxOf(input.minDelayMs, input.maxDelayMs)
                state.windowNs = roll(lo, hi).toLong() * 1_000_000L
            } else {
                state.windowNs = 250_000_000L
            }
        }

        if (input.nowNs - state.holdStartNs >= state.windowNs) {
            state.holdStartNs = -1L
            if (input.modern && input.pulse) {
                state.pauseUntilNs = input.nowNs + state.windowNs
            }
            return false
        }

        return true
    }
}
