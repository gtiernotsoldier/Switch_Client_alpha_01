package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.api.TrackRecord
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Kamui's adapter side, replayed through the real EventBridge: key edge → claim →
 * per-tick refresh → cap release → flush consumed → cooldown. Proves the module
 * marshals the engine into platform writes with the same one-flag semantics the
 * platform executor has always read, and that the TrackRecord feed freezes at the
 * fossil while the phase holds the connection.
 */
class KamuiWiringTest {

    private val triggerKey = 70 // GLFW 'G' in the test, arbitrary but fixed

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        TrackRecord.clear()
        EventBridge.isGuiOpen = false
        ConfigManager.set("Kamui.TriggerKey", triggerKey)
        ConfigManager.set("Kamui.Phase", 3)
        ConfigManager.set("Kamui.MaxHold", 10)
        ConfigManager.set("Kamui.Cooldown", 8)
        ConfigManager.set("Kamui.FlushOnGui", true)
        Kamui.enable()
    }

    @AfterEach
    fun tearDown() {
        Kamui.disable()
        EventBridge.reset()
        TrackRecord.clear()
    }

    private fun player(tick: Long = 0L, health: Float = 20f) =
        PlayerState.EMPTY.copy(health = health, ticks = tick)

    private fun tick(tick: Long = 0L, health: Float = 20f) {
        EventBridge.onTick(player(tick, health), null as TargetState?)
    }

    // ---- Trigger → claim -----------------------------------------------------

    @Test
    fun `a trigger key edge starts the phase and claims the vault`() {
        EventBridge.onKey(triggerKey, true)
        assertTrue(EventBridge.packetLagActive, "the claim lands at the keypress, not the next tick")
        assertEquals("Kamui", EventBridge.packetHoldOwner)
        assertEquals("HOLD 0t", Kamui.hudValue())
    }

    @Test
    fun `a wrong key does nothing`() {
        EventBridge.onKey(71, true)
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `unbound trigger key does nothing`() {
        ConfigManager.set("Kamui.TriggerKey", -1)
        EventBridge.onKey(-1, true)
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `no trigger behind a GUI`() {
        EventBridge.isGuiOpen = true
        EventBridge.onKey(triggerKey, true)
        assertFalse(EventBridge.packetLagActive)
    }

    // ---- The phase, end to end ----------------------------------------------

    @Test
    fun `the phase holds through its ticks then releases exactly once`() {
        EventBridge.onKey(triggerKey, true)

        tick(1)
        tick(2)
        assertTrue(EventBridge.packetLagActive, "still phasing")
        assertEquals("HOLD 2t", Kamui.hudValue())

        // Tick 3 is the cap: claim resolved off, flush request pending.
        tick(3)
        assertFalse(EventBridge.packetLagActive)
        assertEquals("CD 0t", Kamui.hudValue())
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
        assertNull(EventBridge.consumePacketFlush(), "one release, one flush")
    }

    @Test
    fun `the trail freezes at the fossil while phasing and resumes after`() {
        tick(1)
        tick(2)
        val before = TrackRecord.size()
        assertNotEquals(0, before, "idle ticks feed the trail")

        EventBridge.onKey(triggerKey, true)
        tick(3)
        tick(4)
        assertEquals(before, TrackRecord.size(), "held frames are not server-seen — the trail waits")
        val fossil = TrackRecord.lastSent()
        assertEquals(2, fossil?.tick, "the last pushed frame is the fossil the server is looking at")

        // The release tick resumes the feed: the phase ended inside tick 5, so its
        // position is server-seen again (it is part of the burst that just went out).
        tick(5)
        assertEquals(before + 1, TrackRecord.size())
    }

    @Test
    fun `a GUI opening mid-phase ends it early with the flush`() {
        EventBridge.onKey(triggerKey, true)
        tick(1)

        EventBridge.isGuiOpen = true
        tick(2)

        assertFalse(EventBridge.packetLagActive)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
    }

    @Test
    fun `death mid-phase hard-resets to idle`() {
        EventBridge.onKey(triggerKey, true)
        tick(1)

        tick(2, health = 0f)

        assertEquals("ready", Kamui.hudValue(), "hard reset returns to ready")
        assertFalse(EventBridge.packetLagActive)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
        // No cooldown after death: the next trigger lands immediately.
        EventBridge.onKey(triggerKey, true)
        assertTrue(EventBridge.packetLagActive)
    }

    @Test
    fun `a trigger during cooldown is refused`() {
        EventBridge.onKey(triggerKey, true)
        tick(1)
        tick(2)
        tick(3) // cap → release, cooldown starts
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())

        EventBridge.onKey(triggerKey, true)
        assertFalse(EventBridge.packetLagActive, "the cooldown is the charge economy")
        assertNull(EventBridge.consumePacketFlush())
    }

    @Test
    fun `disabling mid-phase releases and flushes`() {
        EventBridge.onKey(triggerKey, true)
        tick(1)
        assertTrue(EventBridge.packetLagActive)

        Kamui.disable()

        assertFalse(EventBridge.packetLagActive, "a half-elapsed phase must not survive a toggle")
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
    }

    @Test
    fun `a co-claimant keeps the flag up through a phase release`() {
        // A routine lag window is live when the phase releases: the backlog must still go
        // out (the flush request is one-sided), but the hold itself continues.
        EventBridge.claimPacketHold("FakeLag")
        EventBridge.onKey(triggerKey, true)
        tick(1)
        tick(2)
        tick(3) // cap → release

        assertTrue(EventBridge.packetLagActive, "FakeLag's claim outlives the phase — hold continues")
        assertEquals("FakeLag", EventBridge.packetHoldOwner)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush(), "but the phase backlog left the queue")

        EventBridge.releasePacketHold("FakeLag")
        assertFalse(EventBridge.packetLagActive)
    }
}
