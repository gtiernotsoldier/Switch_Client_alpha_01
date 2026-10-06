package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.option.ConfigManager
import io.doppel.adapter.common.option.OptionMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * KeepSprint — Raven's `movement/KeepSprint.java`.
 *
 * The module replaces vanilla's post-hit `motion *= 0.6; setSprinting(false)` with its own damp
 * (default `Slow % = 40` → 0.6, i.e. vanilla-looking) and never stops the sprint. These tests pin
 * that math, the `vanilla` short-circuits, and the option wiring (Raven's exact option set).
 */
class KeepSprintTest {

    // ================= Keep factor =================

    @Test
    fun `the default 40 percent equals vanilla's damp`() {
        assertEquals(0.6, KeepSprintLogic.keepFactor(40f), 1e-9)
    }

    @Test
    fun `zero percent keeps the whole swing speed`() {
        assertEquals(1.0, KeepSprintLogic.keepFactor(0f), 1e-9)
    }

    @Test
    fun `the factor interpolates and clamps to Raven's slider`() {
        assertEquals(0.8, KeepSprintLogic.keepFactor(20f), 1e-9)
        assertEquals(0.6, KeepSprintLogic.keepFactor(60f), 1e-9, "above the slider max clamps to 40")
        assertEquals(1.0, KeepSprintLogic.keepFactor(-10f), 1e-9, "below the slider min clamps to 0")
    }

    // ================= Vanilla short-circuits =================

    @Test
    fun `disable while jumping leaves the vanilla damp in place in the air`() {
        assertTrue(KeepSprintLogic.useVanilla(true, onGround = false, onlyReduceReachHits = false, hitDistance = 3f))
        assertFalse(KeepSprintLogic.useVanilla(true, onGround = true, onlyReduceReachHits = false, hitDistance = 3f))
    }

    @Test
    fun `only reduce reach hits hands close hits back to vanilla`() {
        // Raven: `if (distance != -1.0 && distance <= 3.0) vanilla = true;`
        assertTrue(KeepSprintLogic.useVanilla(false, true, onlyReduceReachHits = true, hitDistance = 3.0f))
        assertTrue(KeepSprintLogic.useVanilla(false, true, onlyReduceReachHits = true, hitDistance = 1.2f))
        assertFalse(KeepSprintLogic.useVanilla(false, true, onlyReduceReachHits = true, hitDistance = 3.1f))
    }

    @Test
    fun `both gates off never falls back to vanilla`() {
        assertFalse(KeepSprintLogic.useVanilla(false, onGround = false, onlyReduceReachHits = false, hitDistance = 0.5f))
    }

    // ================= The motion correction =================

    /** Vanilla has already run: a 0.1/0.16 pre-hit sprint motion comes back as 0.06/0.096. */
    private val afterVanillaX = 0.06
    private val afterVanillaZ = 0.096

    @Test
    fun `the default options reproduce vanilla's speed without stopping the sprint`() {
        val c = KeepSprintLogic.correct(afterVanillaX, afterVanillaZ, slowPercent = 40f, stopSprint = true, sprintCap = 0.28)!!
        // scale = 0.6/0.6 = 1 → Z is untouched, X takes Raven's extra stop-sprint half.
        assertEquals(0.096, c.motionZ, 1e-9)
        assertEquals(0.03, c.motionX, 1e-9)
    }

    @Test
    fun `slow zero with stop sprint off restores the pre-hit speed`() {
        val c = KeepSprintLogic.correct(afterVanillaX, afterVanillaZ, slowPercent = 0f, stopSprint = false, sprintCap = 0.28)!!
        assertEquals(0.1, c.motionX, 1e-9, "vanilla's 0.6 is undone and nothing is removed")
        assertEquals(0.16, c.motionZ, 1e-9)
    }

    @Test
    fun `stop sprint halves X only, exactly like the reference`() {
        val kept = KeepSprintLogic.correct(afterVanillaX, afterVanillaZ, 20f, stopSprint = false, sprintCap = 0.28)!!
        val stopped = KeepSprintLogic.correct(afterVanillaX, afterVanillaZ, 20f, stopSprint = true, sprintCap = 0.28)!!
        assertEquals(kept.motionX / 2.0, stopped.motionX, 1e-12)
        assertEquals(kept.motionZ, stopped.motionZ, 1e-12)
    }

    @Test
    fun `the correction can never exceed the observed sprint speed`() {
        // The ceiling is what keeps the "undo vanilla" divide from becoming a boost on a frame where
        // vanilla never applied its damp.
        val c = KeepSprintLogic.correct(afterVanillaX, afterVanillaZ, slowPercent = 0f, stopSprint = false, sprintCap = 0.1)!!
        assertEquals(0.1, sqrt(c.motionX * c.motionX + c.motionZ * c.motionZ), 1e-9)
    }

    @Test
    fun `no ceiling observed yet leaves Raven's plain factor alone`() {
        val c = KeepSprintLogic.correct(afterVanillaX, afterVanillaZ, slowPercent = 0f, stopSprint = false, sprintCap = 0.0)!!
        assertEquals(0.1, c.motionX, 1e-9)
    }

    @Test
    fun `a standing player is left alone`() {
        assertNull(KeepSprintLogic.correct(0.0, 0.0, 40f, true, 0.28))
        assertNull(KeepSprintLogic.correct(1e-6, 0.0, 40f, true, 0.28))
    }

    // ================= Option wiring =================

    @Test
    fun `the option set is Raven's, with its defaults`() {
        val t = KeepSprint.tunables()
        assertEquals(40f, t.slow)
        assertTrue(t.stopSprint)
        assertFalse(t.disableWhileJump)
        assertFalse(t.onlyReduceReachHits)

        val meta: OptionMeta = ConfigManager.getMeta("KeepSprint.Slow")!!
        assertEquals(0f, meta.rangeMin)
        assertEquals(40f, meta.rangeMax)
        assertEquals("%", meta.unit)
        assertEquals(1.0, meta.effectiveStep()!!, 1e-9)
    }
}
