package io.doppel.core.ipc

/**
 * Where randomness comes from, injected rather than reached for.
 *
 * The core used to call `Random.Default` directly from [io.doppel.core.util.MathUtils]
 * and from the condition engine. That is fine for play and fatal for replay:
 * `Random.Default` is seeded from the clock, so the same inputs never produce the
 * same decisions twice, which makes PROTECTION.md 9.5.5 ("same seed, same
 * decisions, diff == 0") unachievable rather than merely unfinished.
 *
 * Production keeps the old behaviour through [DefaultRandomSource]; a replay or a
 * parity run swaps in [DecisionRng].
 */
interface RandomSource {
    /** Uniform in `[0, 1)`. */
    fun nextFloat(): Float

    /** Uniform in `[0, bound)`. */
    fun nextInt(bound: Int): Int

    /** Uniform in `[min, max]`, bounds ordered if swapped. */
    fun randomFloat(min: Float, max: Float): Float

    /** Uniform in `[min, max]` inclusive, bounds ordered if swapped. */
    fun randomInt(min: Int, max: Int): Int
}

/**
 * The existing behaviour: `kotlin.random.Random`, unseeded.
 *
 * Kept as an object rather than folded into [DecisionRng] so that every call site
 * that does not care about determinism keeps exactly the distribution it had.
 */
object DefaultRandomSource : RandomSource {
    override fun nextFloat(): Float = kotlin.random.Random.nextFloat()

    override fun nextInt(bound: Int): Int = kotlin.random.Random.nextInt(bound)

    override fun randomFloat(min: Float, max: Float): Float {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return lo + nextFloat() * (hi - lo)
    }

    override fun randomInt(min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return kotlin.random.Random.nextInt(lo, hi + 1)
    }
}
