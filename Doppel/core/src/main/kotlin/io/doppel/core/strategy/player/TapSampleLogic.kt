package io.doppel.core.strategy.player

/**
 * Test-only Kotlin oracle mirroring `core-rs/src/strategy/tap_sample.rs`.
 *
 * WTap / STap used to draw their post-delay, tap duration and initial hit
 * threshold with `kotlin.random.Random` on the JVM. The draws now live in the
 * core behind a per-module RNG stream; this oracle exists so the 500-row
 * golden replay in `TapSampleParityTest` proves the port kept the same
 * **roll discipline**, not just the same values.
 *
 * The rolls are scripted: Pcg32 and `kotlin.random` are different generators,
 * so comparing their outputs directly would test the RNG, not the machine.
 * The number of consumed rolls is the assertion that catches a port that draws
 * for a degenerate range, draws a value its kind does not use, or draws them
 * in the wrong order.
 *
 * This class is in `core` (the test-only oracle module), never in `agent.jar`.
 */
object TapSampleLogic {
    const val KIND_POST_DELAY_OR_START = 0
    const val KIND_ACTION = 1
    const val KIND_INIT = 2

    data class Input(
        val kind: Int,
        val postDelayMin: Int,
        val postDelayMax: Int,
        val actionMin: Int,
        val actionMax: Int,
        val onceEveryMin: Int,
        val onceEveryMax: Int
    )

    data class Output(
        val postDelayMs: Int = 0,
        val actionMs: Int = 0,
        val threshold: Int = 0
    )

    /** Returns the output and how many scripted rolls it consumed. */
    fun sample(input: Input, rolls: IntArray): Pair<Output, Int> {
        var next = 0

        // Mirrors `Random.nextInt(min, max + 1)`: inclusive on both ends, and
        // a degenerate range is a constant — it never advances the stream.
        fun draw(min: Int, max: Int): Int {
            val lo = min.coerceAtMost(max)
            val hi = min.coerceAtLeast(max)
            if (lo == hi) return lo
            return rolls[next++]
        }

        val out = when (input.kind) {
            KIND_POST_DELAY_OR_START -> Output(
                postDelayMs = draw(input.postDelayMin, input.postDelayMax),
                actionMs = draw(input.actionMin, input.actionMax)
            )
            KIND_ACTION -> Output(actionMs = draw(input.actionMin, input.actionMax))
            KIND_INIT -> Output(
                threshold = draw(input.onceEveryMin, input.onceEveryMax).coerceAtLeast(1)
            )
            else -> error("bad tap sample kind ${input.kind}")
        }
        return out to next
    }

    /** Base-1024 packing; all three fields are bounded by the option layer. */
    fun pack(o: Output): Int = o.postDelayMs + 1024 * o.actionMs + 1024 * 1024 * o.threshold
}
