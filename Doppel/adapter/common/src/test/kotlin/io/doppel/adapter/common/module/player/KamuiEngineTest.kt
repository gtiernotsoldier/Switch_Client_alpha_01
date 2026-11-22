package io.doppel.adapter.common.module.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phase state machine, replayed tick for tick. Deterministic by design — no RNG,
 * no clock — so a whole cast is a fixed sequence of [KamuiEngine.tick] calls.
 */
class KamuiEngineTest {

    private fun engine(phase: Int = 3, hardCap: Int = 10, cooldown: Int = 8) =
        KamuiEngine(KamuiEngine.Config(phase, hardCap, cooldown))

    private fun events(guiOpen: Boolean = false, dead: Boolean = false) =
        KamuiEngine.TickEvents(guiOpen = guiOpen, dead = dead)

    // ---- The one cycle ------------------------------------------------------

    @Test
    fun `trigger starts a phase from idle`() {
        val e = engine()
        assertTrue(e.canTrigger())
        assertTrue(e.trigger())
        assertEquals(KamuiEngine.State.PHASE, e.state)
        assertEquals(1, e.casts)
    }

    @Test
    fun `trigger is refused while busy`() {
        val e = engine()
        e.trigger()
        assertFalse(e.trigger(), "a second key edge must not restart a live phase")
        assertEquals(1, e.casts)
    }

    @Test
    fun `a phase claims every tick and releases exactly once at the cap`() {
        val e = engine(phase = 3)
        e.trigger()

        assertEquals(listOf(KamuiEngine.Command.CLAIM_HOLD), e.tick(events()))
        assertEquals(listOf(KamuiEngine.Command.CLAIM_HOLD), e.tick(events()))
        assertEquals(KamuiEngine.State.PHASE, e.state)

        // Tick 3 is the cap: release, and the phase moves to cooldown.
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.tick(events()))
        assertEquals(KamuiEngine.State.COOLDOWN, e.state)
    }

    @Test
    fun `cooldown returns to idle and frees the trigger`() {
        val e = engine(phase = 1, cooldown = 2)
        e.trigger()
        // Cap on tick 1: straight to release.
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.tick(events()))
        assertEquals(KamuiEngine.State.COOLDOWN, e.state)

        // Two cooldown ticks, then IDLE.
        assertEquals(emptyList<KamuiEngine.Command>(), e.tick(events()))
        assertEquals(KamuiEngine.State.COOLDOWN, e.state)
        assertEquals(emptyList<KamuiEngine.Command>(), e.tick(events()))
        assertEquals(KamuiEngine.State.IDLE, e.state)
        assertTrue(e.canTrigger())
        assertTrue(e.trigger())
        assertEquals(2, e.casts)
    }

    // ---- Guardrails ---------------------------------------------------------

    @Test
    fun `a GUI opening mid-phase ends it with the flush`() {
        val e = engine(phase = 5)
        e.trigger()
        e.tick(events())
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.tick(events(guiOpen = true)))
        assertEquals(KamuiEngine.State.COOLDOWN, e.state)
    }

    @Test
    fun `death hard-resets to idle with no cooldown`() {
        val e = engine(phase = 5, cooldown = 20)
        e.trigger()
        e.tick(events())
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.tick(events(dead = true)))
        assertEquals(KamuiEngine.State.IDLE, e.state)
        assertTrue(e.canTrigger(), "a dead player does not owe a cooldown")
    }

    @Test
    fun `the hard cap bounds a mistuned Phase option`() {
        // Phase says 7, MaxHold says 3: release at tick 3, not 7.
        val e = engine(phase = 7, hardCap = 3)
        e.trigger()
        e.tick(events())
        e.tick(events())
        val cmds = e.tick(events())
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), cmds)
        assertEquals(3, e.phaseTick)
    }

    @Test
    fun `abort releases only when a phase is live`() {
        val e = engine()
        assertEquals(emptyList<KamuiEngine.Command>(), e.abort(), "nothing to release from idle")

        e.trigger()
        e.tick(events())
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.abort())
        assertEquals(KamuiEngine.State.IDLE, e.state)
    }

    // ---- Config retuning ----------------------------------------------------

    @Test
    fun `config changes apply from idle but never mid-phase`() {
        val e = engine(phase = 5, hardCap = 10, cooldown = 0)
        e.trigger()
        e.applyConfig(KamuiEngine.Config(phaseTicks = 1, hardCapTicks = 10, cooldownTicks = 0))
        // Still the old cap: the live cast is not retuned.
        e.tick(events())
        e.tick(events())
        e.tick(events())
        e.tick(events())
        assertEquals(KamuiEngine.State.PHASE, e.state)
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.tick(events()))
        assertEquals(5, e.phaseTick, "released at the ORIGINAL cap — mid-flight retune ignored")

        // Cooldown (0) elapses on the next tick; only from IDLE does the new config apply.
        e.tick(events())
        assertEquals(KamuiEngine.State.IDLE, e.state)
        e.applyConfig(KamuiEngine.Config(phaseTicks = 2, hardCapTicks = 10, cooldownTicks = 0))
        assertEquals(KamuiEngine.Config(phaseTicks = 2, hardCapTicks = 10, cooldownTicks = 0), e.config())
        e.trigger()
        assertEquals(listOf(KamuiEngine.Command.CLAIM_HOLD), e.tick(events()))
        assertEquals(listOf(KamuiEngine.Command.RELEASE_AND_FLUSH), e.tick(events()))
        assertEquals(2, e.phaseTick)
    }
}
