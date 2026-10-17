package io.doppel.core.strategy.movement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SOCD: the later press wins, and when "later" is unknowable nothing happens.
 *
 * The second half is the point. Key state is sampled at 20 Hz, so two keys pressed inside one
 * sample have no observable order — guessing would make the result depend on the order this class
 * happens to check them in, which is worse than vanilla's neutral.
 */
class SocdResolverTest {

    private val r = SocdResolver()

    private fun step(f: Boolean = false, b: Boolean = false, l: Boolean = false, rt: Boolean = false) =
        r.resolve(f, b, l, rt)

    @Test
    fun `one key per axis is never corrected`() {
        assertFalse(step(l = true).any)
        assertFalse(step(rt = true).any)
    }

    @Test
    fun `holding A and then pressing D releases A`() {
        step(l = true)
        val c = step(l = true, rt = true)
        assertTrue(c.suppressLeft)
        assertFalse(c.suppressRight)
    }

    @Test
    fun `holding D and then pressing A releases D`() {
        step(rt = true)
        val c = step(l = true, rt = true)
        assertTrue(c.suppressRight)
        assertFalse(c.suppressLeft)
    }

    @Test
    fun `releasing the newer key gives the older one back`() {
        step(l = true)
        step(l = true, rt = true)
        val c = step(l = true)
        assertFalse(c.any, "with D gone there is nothing to arbitrate")
    }

    @Test
    fun `two keys pressed in the same sample are left to vanilla`() {
        val c = step(l = true, rt = true)
        assertFalse(c.any, "the order is unknowable at this sample rate")
    }

    @Test
    fun `a tie created in one sample persists until one key is re-pressed`() {
        // Both keys appear for the first time in the same sample — e.g. the module was just
        // enabled while the player was already holding them. There is no order to recover, and the
        // tie must not silently resolve itself on a later sample.
        assertFalse(step(l = true, rt = true).any)
        assertFalse(step(l = true, rt = true).any)
        assertFalse(step(l = true, rt = true).any)
    }

    @Test
    fun `the forward axis follows the same rule`() {
        step(f = true)
        val c = step(f = true, b = true)
        assertTrue(c.suppressForward)
        assertFalse(c.suppressBack)
    }

    @Test
    fun `axes are independent`() {
        step(f = true, l = true)
        val c = step(f = true, l = true, rt = true)
        assertTrue(c.suppressLeft)
        assertFalse(c.suppressForward)
    }

    @Test
    fun `a re-press counts as the newest key`() {
        step(l = true)
        step(l = true, rt = true)      // D newest
        step(l = true)                 // D released
        val c = step(l = true, rt = true) // D pressed again -> still newest
        assertTrue(c.suppressLeft)
    }
}
