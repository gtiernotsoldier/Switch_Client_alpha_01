package io.doppel.core.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.doppel.core.rng.DecisionRng

/**
 * Pins [DecisionRng] to the same sequence as `core-rs/src/rng.rs`.
 *
 * These four numbers are the whole cross-language contract for randomness. If
 * Rust and the JVM ever disagree here, every replay after that point diverges,
 * and the cause would look like a strategy bug rather than an RNG one.
 */
class DecisionRngTest {

    @Test
    fun `matches the rust golden vectors`() {
        val r = DecisionRng(0x0123456789ABCDEFL)
        assertEquals(3983312133L, r.nextU32())
        assertEquals(118466452L, r.nextU32())
        assertEquals(62, r.nextInt(100))
        assertEquals(25, r.nextInt(100))
        assertEquals(0.54651016f, r.nextFloat(), 1e-6f)
    }

    @Test
    fun `same seed produces the same sequence`() {
        val a = DecisionRng(42)
        val b = DecisionRng(42)
        repeat(1000) {
            assertEquals(a.nextU32(), b.nextU32())
        }
    }

    @Test
    fun `different seeds diverge`() {
        val a = DecisionRng(1)
        val b = DecisionRng(2)
        var same = 0
        repeat(100) {
            if (a.nextU32() == b.nextU32()) same++
        }
        assertTrue(same < 5, "two seeds produced $same equal draws out of 100")
    }

    @Test
    fun `float stays in the unit interval`() {
        val r = DecisionRng(7)
        repeat(100_000) {
            val f = r.nextFloat()
            assertTrue(f >= 0f && f < 1f, "out of range: $f")
        }
    }

    @Test
    fun `swapped bounds are ordered`() {
        val r = DecisionRng(1)
        repeat(1000) {
            val v = r.randomFloat(0.6f, 0.4f)
            assertTrue(v in 0.4f..0.6f, "got $v")
            val i = r.randomInt(9, 3)
            assertTrue(i in 3..9, "got $i")
        }
    }

    @Test
    fun `int range is inclusive at both ends`() {
        val r = DecisionRng(99)
        var lo = false
        var hi = false
        repeat(10_000) {
            val v = r.randomInt(2, 5)
            assertTrue(v in 2..5)
            if (v == 2) lo = true
            if (v == 5) hi = true
        }
        assertTrue(lo && hi, "range endpoints not both reachable")
    }

    @Test
    fun `zero bound does not throw`() {
        assertEquals(0, DecisionRng(1).nextInt(0))
    }
}
