package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.KeepSprintAssert
import io.doppel.ipc.KeepSprintHold
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * KeepSprint — the sprint STATE, not the sprint SPEED — after the hold
 * machine moved into the core.
 *
 * The sprint-sample latch, the two-dispatch hold window, the wall-clock
 * backstop and the per-tick assert dedup live in `core-rs::strategy::keep_sprint`
 * and are matched frame-for-frame by `KeepSprintParityTest`. What stays here
 * is exactly what the core cannot do:
 *  - the judgements (the attack composite, the crosshair target being alive,
 *    the sprint sample, the forward arbitration, the tap-owns-W override);
 *  - marshalling them into one request per frame;
 *  - turning the packed answer into the sprint-claim / C0B writes;
 *  - the idle-frame short-circuit, which is the original's own early returns
 *    re-ordered — a frame that can change nothing never crosses the wire;
 *  - giving the claim back when the core is silent or reports no hold.
 *
 * The timing semantics these tests used to check inline (one packet per
 * dispatch tick, the latch, the two-tick hold) are now core-side; the
 * Rust unit tests and the parity fixture pin them there.
 */
class KeepSprintTest {

    private lateinit var core: CoreStub
    private val sent = mutableListOf<String>()
    private var coreCalls = 0
    private var answer: Int? = 0
    private var marshalledTick = -1
    private var marshalledSprinting = false
    private var marshalledAttacking = false
    private var marshalledTargetAlive = false
    private var marshalledForward = false
    private var marshalledOverride = false

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        sent.clear()
        coreCalls = 0
        answer = 0
        marshalledTick = -1
        marshalledSprinting = false
        marshalledAttacking = false
        marshalledTargetAlive = false
        marshalledForward = false
        marshalledOverride = false

        core = mockk(relaxed = true)
        // playerKeepSprint: player, target, nowNs, tick, localSprinting,
        // attacking, targetAlive, forward, forwardOverride, tickId
        every {
            core.playerKeepSprint(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            coreCalls++
            marshalledTick = arg(3)
            marshalledSprinting = arg(4)
            marshalledAttacking = arg(5)
            marshalledTargetAlive = arg(6)
            marshalledForward = arg(7)
            marshalledOverride = arg(8)
            answer
        }
        CoreLink.stub = core

        EventBridge.registerEntityActionHandler { sent.add(it) }
        EventBridge.crosshairTarget = null
        EventBridge.isLeftMousePhysicallyDown = false
        EventBridge.syntheticAttack = false
        // The module only preserves a sprint the player already had; the
        // snapshot is normally taken in onStartTick. Tests drive it directly.
        EventBridge.localSprinting = true
        // A sprint implies forward input, and the hold ends the moment the player stops pushing.
        EventBridge.isKeyForwardDown = true
    }

    @AfterEach
    fun tearDown() {
        KeepSprint.disable()
        CoreLink.stub = null
        EventBridge.crosshairTarget = null
        EventBridge.isLeftMousePhysicallyDown = false
        EventBridge.syntheticAttack = false
        EventBridge.localSprinting = false
        EventBridge.isKeyForwardDown = false
        EventBridge.reset()
        sent.clear()
    }

    private fun target(health: Float = 20f) = TargetState(
        entityId = 7,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = health,
        hurtTime = 0,
        distance = 1.5f,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    /** One render frame with the given inputs. */
    private fun frame(attacking: Boolean = false, target: TargetState? = null) {
        EventBridge.crosshairTarget = target
        EventBridge.isLeftMousePhysicallyDown = attacking
        KeepSprint.onRenderFrame(Unit)
    }

    /** Advance one client tick without touching the sprint snapshot. */
    private fun tick() {
        EventBridge.onTick(PlayerState.EMPTY, null)
    }

    // ---- The adapter marshals judgements, it does not decide ----------------

    @Test
    fun `judgements reach the core as inputs`() {
        KeepSprint.enable()
        frame(attacking = true, target = target())

        assertEquals(1, coreCalls)
        assertEquals(EventBridge.getCurrentTick(), marshalledTick)
        assertTrue(marshalledSprinting, "the sprint sample is marshalled")
        assertTrue(marshalledAttacking, "the attack composite is marshalled")
        assertTrue(marshalledTargetAlive, "a live crosshair target is marshalled as alive")
        assertTrue(marshalledForward, "the forward arbitration is marshalled")
        assertFalse(marshalledOverride, "no tap owns W in this test")
    }

    @Test
    fun `a swing at a dead target is marshalled as no alive target`() {
        KeepSprint.enable()
        frame(attacking = true, target = target(health = 0f))
        assertFalse(marshalledTargetAlive)
        // The swing at air never asks at all only when nothing else can
        // change; with the sprint sample true the frame still crosses (the
        // latch needs feeding), and the core decides from targetAlive=false.
        frame(attacking = true, target = null)
        assertFalse(marshalledTargetAlive)
    }

    // ---- The packed answer drives the platform writes -----------------------

    @Test
    fun `an assert step claims sprint and tells the server`() {
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())

        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))
        assertEquals(listOf("START_SPRINTING"), sent)
    }

    @Test
    fun `a release step gives the claim back and sends nothing`() {
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))
        sent.clear()

        answer = KeepSprintHold.RELEASE.code
        frame(attacking = true, target = target())

        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name))
        assertTrue(sent.isEmpty(), "a release writes no packet")
    }

    @Test
    fun `a live hold keeps the claim and writes nothing`() {
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())
        sent.clear()

        answer = KeepSprintHold.LIVE.code
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name), "still claimed")
        assertTrue(sent.isEmpty(), "a continuing hold writes nothing")
    }

    @Test
    fun `a dead hold self-heals a claim the core forgot`() {
        // Another module's Reset frame — or a core restart — can drop the
        // hold while the platform claim survives. DEAD is the status that
        // gives it back.
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))

        answer = KeepSprintHold.DEAD.code
        frame(attacking = true, target = target())
        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name), "the stale claim is gone")
    }

    @Test
    fun `a missing answer releases the claim and sends nothing`() {
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))
        sent.clear()

        answer = null
        frame(attacking = true, target = target())
        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name), "no opinion again — vanilla decides")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a missing answer with no claim writes nothing at all`() {
        KeepSprint.enable()
        answer = null
        frame(attacking = true, target = target())
        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name))
        assertTrue(sent.isEmpty())
    }

    // ---- The idle short-circuit ----------------------------------------------

    @Test
    fun `an idle frame never crosses the wire`() {
        KeepSprint.enable()
        // Not attacking, the sample says not sprinting, no claim live — the
        // original's three early returns, now one guard.
        EventBridge.localSprinting = false
        frame(attacking = false, target = null)
        assertEquals(0, coreCalls, "nothing can change, so nothing is asked")
    }

    @Test
    fun `a sprinting sample keeps the frame crossing for the latch`() {
        KeepSprint.enable()
        EventBridge.localSprinting = true
        frame(attacking = false, target = null)
        assertEquals(1, coreCalls, "the latch is core state — a true sample must feed it")
    }

    @Test
    fun `a live claim keeps the frame crossing for the release checks`() {
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))
        coreCalls = 0
        EventBridge.localSprinting = false

        // Not attacking, sample false — but the claim is live, so the frame
        // still asks: the hold's release conditions (tick window, wall
        // clock, forward) are core-side and only an ask can fire them.
        frame(attacking = false, target = null)
        assertEquals(1, coreCalls)
    }

    @Test
    fun `a disabled module never asks`() {
        KeepSprint.enable()
        KeepSprint.disable()
        EventBridge.localSprinting = true
        frame(attacking = true, target = target())
        assertEquals(0, coreCalls)
    }

    @Test
    fun `disabling mid-hold gives the claim back`() {
        KeepSprint.enable()
        answer = (KeepSprintHold.LIVE.code) or (KeepSprintAssert.ASSERT.code shl 4)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))

        KeepSprint.disable()
        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name))
    }

    @Test
    fun `the numeric knobs are gone`() {
        // Not defaulted off — removed. No configuration can make this module move the client faster
        // than the server's own post-hit model, because that damp is a bilateral rule.
        assertNull(ConfigManager.getMeta("KeepSprint.Slow"))
        assertNull(ConfigManager.getMeta("KeepSprint.StopSprint"))
        assertNull(ConfigManager.getMeta("KeepSprint.DisableWhileJump"))
        assertNull(ConfigManager.getMeta("KeepSprint.OnlyReduceReachHits"))
    }
}
