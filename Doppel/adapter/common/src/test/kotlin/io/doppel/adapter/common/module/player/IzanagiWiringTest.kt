package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.IzanagiState
import io.doppel.ipc.IzanagiStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Izanagi's adapter side: the machine (trail ring, anchor fuse, walk, stall,
 * cooldown, the Kamui arbitration) lives in `core-rs::strategy::izanagi` and is
 * matched tick-for-tick by `IzanagiParityTest`. What stays here is exactly what
 * the core cannot do:
 *  - the judgements (feed = the server can see us, gated by the phase flag;
 *    the corridor check, fail-closed without a platform checker; death; GUI);
 *  - the key edges surviving their hop from the key thread to the next tick;
 *  - the platform write: an advance step becomes a pending replay position,
 *    consumed by the platform's tick loop — and nothing lands without the
 *    platform capability.
 *
 * These tests prove the adapter neither re-decides nor moves on a guess.
 */
class IzanagiWiringTest {

    private val markKey = 82 // GLFW 'R'
    private val recallKey = 70 // GLFW 'F'

    private lateinit var core: CoreStub
    private var coreCalls = 0
    private var resets = 0

    /** The canned answer; null = no answer (deadline miss). */
    private var answer: IzanagiStep? = step()

    private var marshalledFeed = false
    private var marshalledMark = false
    private var marshalledRecall = false
    private var marshalledCorridor = false
    private var corridorAnswer = true

    private fun step(
        state: Int = IzanagiState.IDLE,
        advance: Boolean = false,
        started: Boolean = false,
        aborted: Boolean = false,
        done: Boolean = false,
        remaining: Int = 0,
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0
    ) = IzanagiStep(state, advance, started, aborted, done, remaining, x, y, z)

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        coreCalls = 0
        resets = 0
        answer = step()
        marshalledFeed = false
        marshalledMark = false
        marshalledRecall = false
        marshalledCorridor = false
        corridorAnswer = true

        core = mockk(relaxed = true)
        every {
            core.playerIzanagiTick(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            coreCalls++
            marshalledFeed = arg(2)
            marshalledMark = arg(8)
            marshalledRecall = arg(9)
            marshalledCorridor = arg(10)
            answer
        }
        every { core.reset(any()) } answers { resets++ }
        CoreLink.stub = core

        ConfigManager.set("Izanagi.MarkKey", markKey)
        ConfigManager.set("Izanagi.RecallKey", recallKey)
        ConfigManager.set("Izanagi.MaxDepth", 20)
        ConfigManager.set("Izanagi.StallCap", 5)
        ConfigManager.set("Izanagi.Cooldown", 300)
        Izanagi.enable()
    }

    @AfterEach
    fun tearDown() {
        Izanagi.disable()
        CoreLink.stub = null
        EventBridge.reset()
        EventBridge.corridorChecker = null
        EventBridge.supportsReplayPosition = false
        EventBridge.kamuiPhaseActive = false
    }

    private fun tick() {
        EventBridge.onTick(PlayerState.EMPTY, null as TargetState?)
    }

    @Test
    fun `a mark edge is marshalled and costs nothing on the platform`() {
        EventBridge.onKey(markKey, true)
        tick()
        assertTrue(marshalledMark, "the edge survived its hop to the tick listener")
        assertEquals(1, coreCalls)
        assertNull(EventBridge.pendingReplayPosition, "a mark is free — no platform write")
    }

    @Test
    fun `an advance step lands as a pending replay position`() {
        EventBridge.supportsReplayPosition = true
        EventBridge.corridorChecker = { _, _, _ -> corridorAnswer }
        answer = step(state = IzanagiState.REPLAY, advance = true, remaining = 3, x = 10.5, y = 64.0, z = -3.5)
        tick()
        val p = EventBridge.consumeReplayPosition()
        assertNotNull(p, "the platform write is owed")
        assertEquals(10.5, p!!.x, 0.0)
        assertEquals(64.0, p.y, 0.0)
        assertEquals(-3.5, p.z, 0.0)
        assertNull(EventBridge.consumeReplayPosition(), "one step, one write")
    }

    @Test
    fun `a missing answer moves nothing`() {
        EventBridge.supportsReplayPosition = true
        EventBridge.corridorChecker = { _, _, _ -> true }
        answer = null
        tick()
        assertNull(EventBridge.pendingReplayPosition, "no move on a guess — the safe direction")
        // The feed judgement is marshalled BEFORE the answer exists (it is an
        // input, not a reaction), so a missing answer cannot retroactively
        // freeze it — the trail tolerates gaps by design; the walk only ever
        // targets STORED ticks.
    }

    @Test
    fun `a live kamui phase freezes the trail feed`() {
        EventBridge.kamuiPhaseActive = true
        tick()
        assertFalse(marshalledFeed, "phasing: the reported position is the fossil, not the player")
        // And the flag clearing follows Kamui's own answer ticks.
        EventBridge.kamuiPhaseActive = false
        tick()
        assertTrue(marshalledFeed, "released: the burst went out, this position is server-seen")
    }

    @Test
    fun `a recall without the platform capability is refused at the edge`() {
        // No supportsReplayPosition, no corridor checker: the platform cannot
        // honour a replay — the edge is dropped before the core ever sees it.
        EventBridge.onKey(recallKey, true)
        tick()
        assertFalse(marshalledRecall, "no capability, no recall")
        EventBridge.supportsReplayPosition = true
        EventBridge.onKey(recallKey, true)
        tick()
        assertFalse(marshalledRecall, "a capability without a corridor checker still fails closed")
        EventBridge.corridorChecker = { _, _, _ -> true }
        EventBridge.onKey(recallKey, true)
        tick()
        assertTrue(marshalledRecall, "capability + checker: the edge reaches the core")
    }

    @Test
    fun `enable and disable each send the Reset frame`() {
        val baseline = resets
        Izanagi.disable()
        Izanagi.enable()
        assertEquals(
            baseline + 2, resets,
            "the core must forget the ring and pin on both sides of a toggle"
        )
    }

    @Test
    fun `disabling mid-replay clears the pending write`() {
        EventBridge.supportsReplayPosition = true
        EventBridge.corridorChecker = { _, _, _ -> true }
        answer = step(state = IzanagiState.REPLAY, advance = true, remaining = 2, x = 1.0, y = 64.0, z = 1.0)
        tick()
        assertNotNull(EventBridge.pendingReplayPosition)
        Izanagi.disable()
        assertNull(EventBridge.pendingReplayPosition, "a dead module owes no platform write")
    }
}
