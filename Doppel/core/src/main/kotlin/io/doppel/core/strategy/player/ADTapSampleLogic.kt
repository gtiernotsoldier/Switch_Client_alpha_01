package io.doppel.core.strategy.player

/**
 * Test-only Kotlin oracle mirroring `core-rs/src/strategy/adtap_sample.rs`.
 *
 * ADTap used to draw its fire-point chance roll, its tap duration and its
 * inter-tap gap with `kotlin.random.Random` on the JVM. The draws now live in
 * the core behind ADTap's own RNG stream; this oracle exists so the 500-row
 * golden replay in `ADTapSampleParityTest` proves the port kept the same
 * **roll discipline**, not just the same values.
 *
 * The rolls are scripted: Pcg32 and `kotlin.random` are different generators,
 * so comparing their outputs directly would test the RNG, not the machine.
 * The number of consumed rolls is the assertion that catches a port that rolls
 * a full chance, draws for a degenerate range, or draws the gap where the
 * duration belongs.
 *
 * This class is in `core` (the test-only oracle module), never in `agent.jar`.
 */
object ADTapSampleLogic {
    const val KIND_FIRE = 0
    const val KIND_GAP = 1

    data class Input(
        val kind: Int,
        val chancePercent: Int,
        val tapMinMs: Int,
        val tapMaxMs: Int,
        val gapMinMs: Int,
        val gapMaxMs: Int
    )

    data class Output(
        val passed: Int = 0,
        val durationMs: Int = 0,
        val gapMs: Int = 0
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
            KIND_FIRE -> {
                // The original gate: `chance < 100 && Random.nextInt(100) >= chance`
                // fails. chance == 0 still rolls and always fails, exactly as before.
                val passed = input.chancePercent >= 100 || draw(0, 99) < input.chancePercent
                if (passed) {
                    Output(passed = 1, durationMs = draw(input.tapMinMs, input.tapMaxMs))
                } else {
                    Output(passed = 0, gapMs = draw(input.gapMinMs, input.gapMaxMs))
                }
            }
            KIND_GAP -> Output(gapMs = draw(input.gapMinMs, input.gapMaxMs))
            else -> error("bad adtap sample kind ${input.kind}")
        }
        return out to next
    }

    /** Base-1024 packing; every field is bounded by the option layer. */
    fun pack(o: Output): Int = o.durationMs + 1024 * o.gapMs + 1024 * 1024 * o.passed
}
