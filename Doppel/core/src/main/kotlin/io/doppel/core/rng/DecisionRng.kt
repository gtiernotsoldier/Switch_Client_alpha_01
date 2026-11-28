package io.doppel.core.rng

import io.doppel.contract.rng.RandomSource

/**
 * PCG32, bit-identical to `core-rs/src/rng.rs`.
 *
 * This is a deliberate second implementation of a twenty-line algorithm rather
 * than a shared library: one of them runs on the JVM inside the game, the other
 * in the core process, and they cannot share code across the boundary by
 * construction. What keeps them honest is the golden vector test on both sides —
 * [DecisionRngTest] here asserts the same numbers as `rng_golden_vectors` and
 * `stream_golden_vectors` in Rust. If either drifts, that suite goes red before
 * a replay ever runs.
 *
 * Arithmetic notes, because this is where cross-language ports usually break:
 *  - `Long` overflow wraps in Kotlin, same as `wrapping_mul` / `wrapping_add`.
 *  - `ushr` is the logical shift, matching `>>` on a Rust `u64`.
 *  - `Integer.rotateRight` is a 32-bit rotate, matching `u32::rotate_right`.
 *  - `nextFloat` uses the top 24 bits, exactly representable in f32, so the JVM
 *    and Rust produce the same float without a rounding step.
 *  - the stream form uses `inc = 2 * streamId + 1`, the PCG stream selector,
 *    exactly like `Pcg32::with_stream` in Rust.
 *
 * Not thread-safe, by design: a session owns one, and a session is one thread.
 *
 * @param seed the session seed, same on both sides.
 * @param streamId the module stream selector — see [Streams]. `0` is reserved
 *   for the legacy fixed-increment sequence (what the one-argument constructor
 *   and `Pcg32::new` produce); the twenty session streams use 1..20.
 */
class DecisionRng(seed: Long, streamId: Long = 0L) : RandomSource {

    private var state: Long = 0L
    private val inc: Long

    init {
        inc = if (streamId == 0L) LEGACY_INC else (streamId shl 1) or 1L
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
        state = old * MULT + inc
        val xorshifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return Integer.rotateRight(xorshifted, rot).toLong() and 0xFFFF_FFFFL
    }

    override fun nextFloat(): Float = (nextU32() ushr 8).toFloat() / 16_777_216.0f

    /**
     * 32-bit resolution, matching `Pcg32::next_double` in Rust.
     */
    override fun nextDouble(): Double = nextU32().toDouble() / 4_294_967_296.0

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

    /**
     * Mirror of `session.rs::stream` — the twenty module stream ids, one per
     * module family. Append-only, never renumber: a parity test that
     * reconstructs a module's stream must use the same id the session uses,
     * and the golden fixtures bake the ids in.
     */
    object Streams {
        const val VELOCITY: Long = 1L
        const val AIM: Long = 2L
        const val CLICK: Long = 3L
        const val TAP: Long = 4L
        const val WTAP: Long = 5L
        const val STAP: Long = 6L
        const val ADTAP: Long = 7L
        const val COMBAT: Long = 8L
        const val AUTOBLOCK: Long = 9L
        const val BLOCKHIT: Long = 10L
        const val PLAYER: Long = 11L
        const val BACKTRACK: Long = 12L
        const val KBD: Long = 13L
        const val REACH: Long = 14L
        const val CLICK_ASSIST: Long = 15L
        const val HIT_SELECT: Long = 16L
        const val EAGLE_PLACE: Long = 17L
        const val SPRINT_RESET: Long = 18L
        const val JUMP_RESET: Long = 19L
        const val SUPER_KNOCKBACK: Long = 20L
    }

    private companion object {
        private const val MULT: Long = 6364136223846793005L
        private const val LEGACY_INC: Long = 1442695040888963407L
    }
}
