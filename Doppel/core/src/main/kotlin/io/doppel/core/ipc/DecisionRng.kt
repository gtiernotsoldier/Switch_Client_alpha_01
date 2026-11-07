package io.doppel.core.ipc

/**
 * PCG32, bit-identical to `core-rs/src/rng.rs`.
 *
 * This is a deliberate second implementation of a twenty-line algorithm rather
 * than a shared library: one of them runs on the JVM inside the game, the other
 * in the core process, and they cannot share code across the boundary by
 * construction. What keeps them honest is the golden vector test on both sides —
 * [DecisionRngTest] here asserts the same four numbers as `rng_golden_vectors` in
 * Rust. If either drifts, that suite goes red before a replay ever runs.
 *
 * Arithmetic notes, because this is where cross-language ports usually break:
 *  - `Long` overflow wraps in Kotlin, same as `wrapping_mul` / `wrapping_add`.
 *  - `ushr` is the logical shift, matching `>>` on a Rust `u64`.
 *  - `Integer.rotateRight` is a 32-bit rotate, matching `u32::rotate_right`.
 *  - `nextFloat` uses the top 24 bits, exactly representable in f32, so the JVM
 *    and Rust produce the same float without a rounding step.
 *
 * Not thread-safe, by design: a session owns one, and a session is one thread.
 */
class DecisionRng(seed: Long) : RandomSource {

    private var state: Long = 0L

    init {
        // Standard PCG seeding: two steps, so the first output is not a simple
        // function of the seed.
        state += seed
        nextU32()
        state += seed
        nextU32()
    }

    /** Raw 32-bit output, widened to Long so the caller does not fight sign bits. */
    fun nextU32(): Long {
        val old = state
        state = old * MULT + INC
        val xorshifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return Integer.rotateRight(xorshifted, rot).toLong() and 0xFFFF_FFFFL
    }

    override fun nextFloat(): Float = (nextU32() ushr 8).toFloat() / 16_777_216.0f

    /**
     * Modulo, not rejection sampling — see the Rust twin for why. The bias is
     * ~2e-7 at bound 100, far below anything measurable in play.
     */
    override fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        return (nextU32() % bound.toLong()).toInt()
    }

    override fun randomFloat(min: Float, max: Float): Float {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return lo + nextFloat() * (hi - lo)
    }

    override fun randomInt(min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val span = (hi.toLong() - lo.toLong() + 1L).toInt()
        return lo + nextInt(span)
    }

    private companion object {
        const val MULT: Long = 6364136223846793005L
        const val INC: Long = 1442695040888963407L
    }
}
