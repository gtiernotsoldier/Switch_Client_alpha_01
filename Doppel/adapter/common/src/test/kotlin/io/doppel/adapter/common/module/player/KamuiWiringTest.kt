package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.api.TrackRecord
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.KamuiAnswer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Kamui's adapter side after the phase machine moved into the core.
 *
 * The IDLE / PHASE / COOLDOWN machine, the tick counters, the cast counter and
 * the knob set latched at the trigger live in `core-rs::strategy::kamui` and
 * are matched tick-for-tick by `KamuiParityTest`. What stays here is exactly
 * what the core cannot do:
 *  - the judgements (death, GUI under FlushOnGui, the trigger key edge that
 *    must survive its hop from the key thread to the next tick);
 *  - marshalling the six arguments into one request per tick;
 *  - turning the packed answer into the vault claim / release / flush writes;
 *  - letting everything through when the core is silent (release, no flush).
 *
 * These tests prove the adapter neither re-decides nor holds packets on a
 * guess: no `kotlin.random`, no local deadline, no cross-tick state beyond the
 * one `@Volatile` edge flag and the HUD's last answer.
 */
class KamuiWiringTest {

    private val triggerKey = 70 // GLFW 'G' in the test, arbitrary but fixed

    private lateinit var core: CoreStub
    private var coreCalls = 0
    private var resets = 0

    /** The packed answer the fake core returns; null = no answer (deadline miss). */
    private var answer: Int? = pack(KamuiAnswer.STATE_IDLE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)

    private var marshalledTriggerEdge = false
    private var marshalledGui = false
    private var marshalledDead = false
    private var marshalledPhaseTicks = -1
    private var marshalledMaxHoldTicks = -1
    private var marshalledCooldownTicks = -1

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        coreCalls = 0
        resets = 0
        answer = pack(KamuiAnswer.STATE_IDLE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        marshalledTriggerEdge = false
        marshalledGui = false
        marshalledDead = false
        marshalledPhaseTicks = -1
        marshalledMaxHoldTicks = -1
        marshalledCooldownTicks = -1

        // Not covered by EventBridge.reset() — a stale hold from a previous
        // test would leak into the assertions below.
        EventBridge.packetLagActive = false
        TrackRecord.clear()
        EventBridge.isGuiOpen = false

        core = mockk(relaxed = true)
        // playerKamuiTick: player, target, triggerEdge, guiOpen, dead,
        // phaseTicks, maxHoldTicks, cooldownTicks, tick
        every {
            core.playerKamuiTick(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            coreCalls++
            marshalledTriggerEdge = arg(2)
            marshalledGui = arg(3)
            marshalledDead = arg(4)
            marshalledPhaseTicks = arg(5)
            marshalledMaxHoldTicks = arg(6)
            marshalledCooldownTicks = arg(7)
            answer
        }
        every { core.reset(any()) } answers { resets++ }
        CoreLink.stub = core

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
        CoreLink.stub = null
        EventBridge.reset()
        EventBridge.packetLagActive = false
        TrackRecord.clear()
    }

    /**
     * Mirrors the documented `PLAYER_KAMUI_TICK` answer layout (`Wire.Ops`,
     * `strategy::kamui::pack`) so the test can drive the module with canned
     * machine states without a live core.
     */
    private fun pack(state: Int, claim: Boolean, release: Boolean, phaseTick: Int, cooldownTick: Int): Int =
        (state and 0xF) or
            (if (claim) 1 shl 4 else 0) or
            (if (release) 2 shl 4 else 0) or
            ((phaseTick and 0xFFF) shl 8) or
            ((cooldownTick and 0xFFF) shl 20)

    private fun player(tick: Long = 0L, health: Float = 20f) =
        PlayerState.EMPTY.copy(health = health, ticks = tick)

    private fun tick(tick: Long = 0L, health: Float = 20f) {
        EventBridge.onTick(player(tick, health), null as TargetState?)
    }

    // ---- Trigger → claim -----------------------------------------------------

    @Test
    fun `a trigger key edge claims the vault at the keypress without asking the core`() {
        EventBridge.onKey(triggerKey, true)
        assertTrue(EventBridge.packetLagActive, "the claim lands at the keypress, not the next tick")
        assertEquals("Kamui", EventBridge.packetHoldOwner)
        assertEquals(0, coreCalls, "the keypress itself never round-trips")
        // The machine lives in the core, which has not been told yet — the HUD
        // shows the last answer until this tick's request lands.
        assertEquals("ready", Kamui.hudValue())
    }

    @Test
    fun `the tick after the edge marshals it and starts the phase`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)

        tick(1)

        assertEquals(1, coreCalls)
        assertTrue(marshalledTriggerEdge)
        assertFalse(marshalledGui)
        assertFalse(marshalledDead)
        assertEquals(3, marshalledPhaseTicks)
        assertEquals(10, marshalledMaxHoldTicks)
        assertEquals(8, marshalledCooldownTicks)
        assertTrue(EventBridge.packetLagActive, "the keypress claim covers the gap until the first CLAIM flag")
        assertEquals("HOLD 0t", Kamui.hudValue())
        assertTrue(Kamui.hudHighlight())
    }

    @Test
    fun `a wrong key does nothing`() {
        EventBridge.onKey(71, true)
        assertFalse(EventBridge.packetLagActive)
        tick(1)
        assertFalse(marshalledTriggerEdge)
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
        assertFalse(EventBridge.packetLagActive, "a phase the player cannot steer is not started")
    }

    // ---- The phase, end to end ----------------------------------------------

    @Test
    fun `the phase holds through its ticks then releases exactly once`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(1)

        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(2)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 2, cooldownTick = 0)
        tick(3)
        assertTrue(EventBridge.packetLagActive, "still phasing")
        assertEquals("HOLD 2t", Kamui.hudValue())

        // Tick 4 is the cap: claim resolved off, flush request pending.
        answer = pack(KamuiAnswer.STATE_COOLDOWN, claim = false, release = true, phaseTick = 3, cooldownTick = 0)
        tick(4)
        assertFalse(EventBridge.packetLagActive)
        assertEquals("CD 0t", Kamui.hudValue())
        assertFalse(Kamui.hudHighlight())
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
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(3)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(4)
        assertEquals(before, TrackRecord.size(), "held frames are not server-seen — the trail waits")
        val fossil = TrackRecord.lastSent()
        assertEquals(2, fossil?.tick, "the last pushed frame is the fossil the server is looking at")

        // The release tick resumes the feed: the phase ended inside tick 5, so its
        // position is server-seen again (it is part of the burst that just went out).
        answer = pack(KamuiAnswer.STATE_COOLDOWN, claim = false, release = true, phaseTick = 2, cooldownTick = 0)
        tick(5)
        assertEquals(before + 1, TrackRecord.size())
    }

    @Test
    fun `a GUI opening mid-phase ends it early with the flush`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(1)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(2)

        EventBridge.isGuiOpen = true
        answer = pack(KamuiAnswer.STATE_COOLDOWN, claim = false, release = true, phaseTick = 2, cooldownTick = 0)
        tick(3)

        assertTrue(marshalledGui, "the GUI judgement is marshalled under FlushOnGui")
        assertFalse(EventBridge.packetLagActive)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
    }

    @Test
    fun `death mid-phase releases with a hard reset to ready`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(1)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(2)

        answer = pack(KamuiAnswer.STATE_IDLE, claim = false, release = true, phaseTick = 0, cooldownTick = 0)
        tick(3, health = 0f)

        assertTrue(marshalledDead, "the death judgement is marshalled")
        assertEquals("ready", Kamui.hudValue(), "hard reset returns to ready")
        assertFalse(EventBridge.packetLagActive)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
        // No cooldown after death: the next trigger lands immediately.
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        EventBridge.onKey(triggerKey, true)
        assertTrue(EventBridge.packetLagActive)
    }

    // ---- Refused edges -------------------------------------------------------

    @Test
    fun `a trigger during cooldown is refused and drops the keypress claim`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_COOLDOWN, claim = false, release = false, phaseTick = 0, cooldownTick = 3)
        tick(1)

        assertTrue(marshalledTriggerEdge, "the edge was marshalled")
        assertFalse(
            EventBridge.packetLagActive,
            "the cooldown is the charge economy — the keypress claim must not outlive it"
        )
        assertNull(EventBridge.consumePacketFlush(), "a refused edge never flushes")
    }

    @Test
    fun `a trigger during a live phase merges with the phase claim`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(1)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(2)

        // A second press mid-phase: refused by the core, but the claim is the
        // phase's own — its CLAIM flags refresh it, its RELEASE drops it. The
        // adapter must NOT drop it here (that would open a one-tick hold gap).
        EventBridge.onKey(triggerKey, true)
        tick(3)

        assertTrue(EventBridge.packetLagActive, "the live phase keeps holding")
        assertEquals("Kamui", EventBridge.packetHoldOwner)
    }

    // ---- Degradation ---------------------------------------------------------

    @Test
    fun `a missing answer releases the claim and never flushes`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(1)
        assertTrue(EventBridge.packetLagActive)

        answer = null
        tick(2)

        assertFalse(EventBridge.packetLagActive, "the safe direction is not to hold at all")
        assertNull(EventBridge.consumePacketFlush(), "no answer, no flush")
    }

    @Test
    fun `a disabled module asks nothing`() {
        Kamui.disable()
        coreCalls = 0
        tick(1)
        assertEquals(0, coreCalls)
    }

    // ---- Reset semantics (CONTRIBUTING step 5) --------------------------------

    @Test
    fun `enable and disable each send the Reset frame`() {
        val baseline = resets
        Kamui.disable()
        Kamui.enable()
        assertEquals(
            baseline + 2, resets,
            "the core must forget the cast on both sides of a toggle, or a re-enable resumes it"
        )
    }

    @Test
    fun `disabling mid-phase releases and flushes`() {
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(1)
        assertTrue(EventBridge.packetLagActive)

        Kamui.disable()

        assertFalse(EventBridge.packetLagActive, "a half-elapsed phase must not survive a toggle")
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
    }

    @Test
    fun `disabling with an unconsumed edge releases and flushes too`() {
        // The keypress claimed, but the tick that would have told the core never
        // ran: the edge itself is the witness of a live phase.
        EventBridge.onKey(triggerKey, true)
        assertTrue(EventBridge.packetLagActive)

        Kamui.disable()

        assertFalse(EventBridge.packetLagActive)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
    }

    @Test
    fun `disabling while idle releases without a flush`() {
        Kamui.disable()
        assertNull(EventBridge.consumePacketFlush(), "nothing was held, nothing to flush")
    }

    // ---- Co-claimants ----------------------------------------------------------

    @Test
    fun `a co-claimant keeps the flag up through a phase release`() {
        // A routine lag window is live when the phase releases: the backlog must still go
        // out (the flush request is one-sided), but the hold itself continues.
        EventBridge.claimPacketHold("FakeLag")
        EventBridge.onKey(triggerKey, true)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = false, release = false, phaseTick = 0, cooldownTick = 0)
        tick(1)
        answer = pack(KamuiAnswer.STATE_PHASE, claim = true, release = false, phaseTick = 1, cooldownTick = 0)
        tick(2)
        answer = pack(KamuiAnswer.STATE_COOLDOWN, claim = false, release = true, phaseTick = 2, cooldownTick = 0)
        tick(3)

        assertTrue(EventBridge.packetLagActive, "FakeLag's claim outlives the phase — hold continues")
        assertEquals("FakeLag", EventBridge.packetHoldOwner)
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush(), "but the phase backlog left the queue")

        EventBridge.releasePacketHold("FakeLag")
        assertFalse(EventBridge.packetLagActive)
    }
}
