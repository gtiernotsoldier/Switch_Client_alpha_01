package io.doppel.core.strategy.player

/**
 * Test-only Kotlin oracle mirroring `core-rs/src/strategy/blockhit_sample.rs`.
 *
 * BlockHit used to draw its post-delay, its block duration and its on-enable
 * hit threshold with `kotlin.random.Random` on the JVM. The draws now live in
 * the core behind BlockHit's own RNG stream; this oracle exists so the
 * 500-row golden replay in `BlockHitSampleParityTest` proves the port kept
 * the same **draw discipline**, not just the same values.
 *
 * The rolls are scripted: Pcg32 and `kotlin.random` are different generators,
 * so comparing their outputs directly would test the RNG, not the machine.
 * The number of consumed rolls is the assertion that catches a port that
 * swaps the two fire draws, draws for a degenerate range, or forgets the
 * `coerceAtLeast(1)` clamp on the threshold.
 *
 * This class is in `core` (the test-only oracle module), never in `agent.jar`.
 */
object BlockHitSampleLogic {
    const val KIND_FIRE = 0
    const val KIND_INIT = 1

    data class Input(
        val kind: Int,
        val postDelayMin: Int,
        val postDelayMax: Int,
        val waitMin: Int,
        val waitMax: Int,
        val hitPerMin: Int,
        val hitPerMax: Int
    )

    data class Output(
        val postDelayMs: Int = 0,
        val waitMs: Int = 0,
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
            KIND_FIRE -> Output(
                postDelayMs = draw(input.postDelayMin, input.postDelayMax),
                waitMs = draw(input.waitMin, input.waitMax)
            )
            KIND_INIT -> Output(threshold = draw(input.hitPerMin, input.hitPerMax).coerceAtLeast(1))
            else -> error("bad blockhit sample kind ${input.kind}")
        }
        return out to next
    }

    /** Base-1024 packing; every field is bounded by the option layer. */
    fun pack(o: Output): Int = o.postDelayMs + 1024 * o.waitMs + 1024 * 1024 * o.threshold
}
