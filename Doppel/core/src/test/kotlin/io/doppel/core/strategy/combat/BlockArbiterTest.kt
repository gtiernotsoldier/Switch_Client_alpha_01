package io.doppel.core.strategy.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BlockArbiter — the combination rules the Adaptive mode depends on.
 *
 * These pin the structure rather than the arithmetic, because the structure is the design: which
 * signals may hold alone, which needs company, and what the duty budget overrides. A pure function
 * with no platform dependency, so there is no excuse for it not being covered.
 */
class BlockArbiterTest {

    private val defaults = BlockArbiter.Weights()

    private fun eval(
        locked: Boolean = false,
        threat: Boolean = false,
        sustain: Boolean = false,
        reflex: Boolean = false,
        weights: BlockArbiter.Weights = defaults,
        threshold: Double = BlockArbiter.DEFAULT_THRESHOLD,
        duty: Double = 0.0,
        maxDuty: Double = 1.0
    ) = BlockArbiter.evaluate(locked, threat, sustain, reflex, weights, threshold, duty, maxDuty)

    // ================= which signal holds alone =================

    @Test
    fun `the damage lock holds on its own`() {
        assertTrue(eval(locked = true).block)
    }

    @Test
    fun `a predicted attack holds on its own`() {
        assertTrue(eval(threat = true).block)
    }

    @Test
    fun `the sag signal holds on its own`() {
        assertTrue(eval(sustain = true).block, "Adaptive must not be weaker than the mode it replaces")
    }

    @Test
    fun `just swinging does not hold on its own`() {
        assertFalse(eval(reflex = true).block, "the least informative signal must not act alone")
    }

    @Test
    fun `but it does in company`() {
        assertTrue(eval(sustain = true, reflex = true).block)
    }

    @Test
    fun `nothing firing holds nothing`() {
        assertFalse(eval().block)
    }

    // ================= what the verdict reports =================

    @Test
    fun `the verdict names what fired`() {
        assertEquals(listOf("lock", "reflex"), eval(locked = true, reflex = true).firing)
    }

    @Test
    fun `a losing verdict still names what fired`() {
        assertEquals(listOf("reflex"), eval(reflex = true).firing, "useful for a HUD or a log")
    }

    @Test
    fun `the score is the sum of the firing weights`() {
        assertEquals(1.5, eval(locked = true, sustain = true).score, 1e-9)
    }

    // ================= weights and threshold are real knobs =================

    @Test
    fun `a zero weight removes a signal entirely`() {
        val noSag = BlockArbiter.Weights(sustain = 0.0)
        val v = eval(sustain = true, weights = noSag)
        assertFalse(v.block, "weight 0 is how a mode is switched off inside Adaptive")
        assertEquals(0.0, v.score, 1e-9)
    }

    @Test
    fun `raising the threshold requires agreement`() {
        assertFalse(eval(sustain = true, threshold = 0.7).block)
        assertTrue(eval(sustain = true, reflex = true, threshold = 0.7).block)
    }

    // ================= the duty gate =================

    @Test
    fun `over budget the weak signals stop holding`() {
        val v = eval(sustain = true, duty = 0.8, maxDuty = 0.5)
        assertFalse(v.block)
        assertTrue(v.throttled, "the caller needs to be able to tell throttling from inactivity")
    }

    @Test
    fun `over budget the damage lock still holds`() {
        val v = eval(locked = true, duty = 0.8, maxDuty = 0.5)
        assertTrue(v.block, "the lock is free value — it is not behaviour that needs rationing")
        assertFalse(v.throttled)
    }

    @Test
    fun `at the budget exactly it still holds`() {
        assertTrue(eval(sustain = true, duty = 0.5, maxDuty = 0.5).block, "the comparison is strict")
    }

    @Test
    fun `the gate is off at the default budget`() {
        assertTrue(eval(sustain = true, duty = 1.0, maxDuty = 1.0).block)
    }

    // ================= the rolling duty window =================

    @Test
    fun `the window reports the fraction held`() {
        val state = BlockArbiter.State(windowTicks = 4)
        assertEquals(0.0, state.duty(), 1e-9)
        state.record(true)
        state.record(true)
        state.record(false)
        state.record(false)
        assertEquals(0.5, state.duty(), 1e-9)
    }

    @Test
    fun `duty is over what has been seen, not the whole window`() {
        val state = BlockArbiter.State(windowTicks = 10)
        state.record(true)
        assertEquals(1.0, state.duty(), 1e-9, "one held tick out of one observed is 100%, not 10%")
    }

    @Test
    fun `the window rolls`() {
        val state = BlockArbiter.State(windowTicks = 2)
        state.record(true)
        state.record(false)
        assertEquals(0.5, state.duty(), 1e-9)
        state.record(false)
        state.record(false)
        assertEquals(0.0, state.duty(), 1e-9, "the held tick has rolled out of the window")
    }

    @Test
    fun `reset clears the window`() {
        val state = BlockArbiter.State(windowTicks = 4)
        state.record(true)
        state.record(true)
        state.reset()
        assertEquals(0.0, state.duty(), 1e-9)
    }
}
