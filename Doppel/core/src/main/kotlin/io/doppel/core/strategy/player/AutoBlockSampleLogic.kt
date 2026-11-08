package io.doppel.core.strategy.player

/**
 * Test-only Kotlin oracle mirroring `core-rs/src/strategy/autoblock_sample.rs`.
 *
 * AutoBlock used to roll its per-tick Chance gate and its Lag window start
 * with `kotlin.random.Random` on the JVM. Both rolls now live in the core
 * behind AutoBlock's own RNG stream; this oracle exists so the 500-row golden
 * replay in `AutoBlockSampleParityTest` proves the port kept the same
 * **roll discipline**, not just the same values.
 *
 * The rolls are scripted: Pcg32 and `kotlin.random` are different generators,
 * so comparing their outputs directly would test the RNG, not the gate. The
 * number of consumed rolls is the assertion that catches a port that rolls a
 * full chance, skips the roll at chance 0, or rolls a range other than
 * `nextInt(100)`.
 *
 * This class is in `core` (the test-only oracle module), never in `agent.jar`.
 */
object AutoBlockSampleLogic {

    /** Returns the verdict and how many scripted rolls it consumed. */
    fun sample(chancePercent: Int, rolls: IntArray): Pair<Boolean, Int> {
        var next = 0

        // Mirrors `Random.nextInt(100)`: [0, 100), consumed only when the
        // `||` did not already short-circuit on a full chance.
        fun roll(): Int = rolls[next++]

        // The original gate: `chance < 100 && Random.nextInt(100) >= chance`
        // fails. chance == 0 still rolls and always fails, exactly as before.
        val passed = chancePercent >= 100 || roll() < chancePercent
        return passed to next
    }
}
