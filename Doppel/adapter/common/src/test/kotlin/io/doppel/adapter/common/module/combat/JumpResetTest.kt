package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.util.Vec3
import io.doppel.ipc.CombatDecision
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.JumpResetStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JumpReset's adapter side.
 *
 * The stateful machine (chance roll, Ticks / ReceivedHits cooldown, delayed
 * jump and its deadline) lives in `core-rs::strategy::jump_reset` and is
 * matched event-for-event by `JumpResetParityTest`. What stays here is exactly
 * what the core cannot do:
 *  - the pure judgements (internal conditions, front-angle geometry, unified
 *    gate, and the final "still sprinting at jump time" check);
 *  - marshalling those judgements and the tuning knobs into one request per
 *    knock and one per tick;
 *  - turning the returned [JumpResetStep] into the single platform write;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor fires a stray jump:
 * no `Random`, no local deadline, no cross-tick counters.
 */
class JumpResetTest {

    private lateinit var core: CoreStub
    private var jumps = 0
    private var gateOk = true
    private var gateCalls = 0
    private var knockCode: Int? = JumpResetStep.NONE.code
    private var pumpCode: Int? = JumpResetStep.NONE.code
    private var lastGatesOpen = true
    private var lastCooldownMode = -1

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        jumps = 0
        gateOk = true
        gateCalls = 0
        knockCode = JumpResetStep.NONE.code
        pumpCode = JumpResetStep.NONE.code
        lastGatesOpen = true
        lastCooldownMode = -1

        core = mockk(relaxed = true)
        // The unified gate goes through combatDecide; the adapter only reads
        // its `fire` bit and folds it into gatesOpen, nothing more.
        every {
            core.combatDecide(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { gateCalls++; CombatDecision(gateOk, 0, 1) }
        // playerJumpKnock: player, target, gatesOpen, nowNs, chance,
        // cooldownMode, ticksUntil, hitsUntil, delayMs, tick
        every {
            core.playerJumpKnock(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            lastGatesOpen = arg(2)
            lastCooldownMode = arg(5)
            knockCode
        }
        // playerJumpPump: player, target, nowNs, cooldownMode, ticksUntil, tick
        every {
            core.playerJumpPump(any(), any(), any(), any(), any(), any())
        } answers {
            lastCooldownMode = arg(3)
            pumpCode
        }
        CoreLink.stub = core

        EventBridge.registerJumpHandler { jumps++ }
        // Keep the tuning knobs at unambiguous values so the marshalled inputs
        // are easy to assert on.
        ConfigManager.set("JumpReset.Chance", 100)
        ConfigManager.set("JumpReset.Delay", 0)
        ConfigManager.set("JumpReset.CooldownMode", "ReceivedHits")
        ConfigManager.set("JumpReset.HitsUntilJump", 1)
        ConfigManager.set("JumpReset.TicksUntilJump", 4)
        JumpReset.enable()
    }

    @AfterEach
    fun tearDown() {
        JumpReset.disable()
        CoreLink.stub = null
        EventBridge.reset()
        // The option store is process-global: restore JumpReset's declared defaults.
        ConfigManager.set("JumpReset.Chance", 100)
        ConfigManager.set("JumpReset.Delay", 0)
        ConfigManager.set("JumpReset.CooldownMode", "Ticks")
        ConfigManager.set("JumpReset.HitsUntilJump", 2)
        ConfigManager.set("JumpReset.TicksUntilJump", 4)
    }

    /** A sprinting, grounded player facing +Z (yaw 0). */
    private fun sprintingPlayer(
        sprinting: Boolean = true,
        onGround: Boolean = true,
        yaw: Float = 0f
    ) = PlayerState(
        "tester", 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, yaw, 0f,
        onGround, false, sprinting, 20f, 0, 1L
    )

    private fun target() = TargetState(
        entityId = 7,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = 20f,
        hurtTime = 5,
        distance = 2f,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    /** Knockback away from the facing direction, i.e. the attacker is in front. */
    private fun knockbackMotion() = Vec3(0.0, 0.4, -1.0)

    /**
     * One velocity packet followed by the render frame that drains the queued
     * jump. `queueJump` only sets a flag; the jump key is pressed on the main
     * thread via `drainPendingJump`, so a test that skips the drain would see
     * no handler call at all.
     */
    private fun knock(player: PlayerState = sprintingPlayer(), motion: Vec3 = knockbackMotion()) {
        EventBridge.notifyVelocityPacket(VelocityContext(motion, player, target(), Unit))
        EventBridge.drainPendingJump()
    }

    private fun tick(player: PlayerState = sprintingPlayer()) {
        EventBridge.onTick(player, target())
        EventBridge.drainPendingJump()
    }

    // ---- The adapter marshals judgements, it does not decide ----------------

    @Test
    fun `judgements reach the core as one gates_open bit`() {
        knock()

        assertTrue(lastGatesOpen, "all internal conditions and the gate fold into gatesOpen")
        assertEquals(1, lastCooldownMode, "ReceivedHits maps to wire mode code 1")
        assertEquals(1, gateCalls, "the unified gate was asked once")
    }

    @Test
    fun `a failed internal condition short-circuits the unified gate`() {
        knock(sprintingPlayer(onGround = false))

        assertFalse(lastGatesOpen, "an airborne player closes the gates")
        assertEquals(0, gateCalls, "a failed internal condition never asks the gate")
        assertEquals(0, jumps, "closed gates never jump")
    }

    @Test
    fun `sprint is a gate condition now, not a pre-gate short circuit`() {
        // OnlySprint became a user-facing switch, so the check moved into the
        // unified gate. The gate is asked on a non-sprinting knock; it is the
        // gate's answer, not an early return here, that closes the gates.
        gateOk = false
        knock(sprintingPlayer(sprinting = false))

        assertFalse(lastGatesOpen)
        assertEquals(1, gateCalls, "a non-sprinting knock still asks the gate")
        assertEquals(0, jumps, "a closed gate never jumps")
    }

    @Test
    fun `a knockback from behind closes the gates`() {
        // Motion towards +Z means the attacker is behind the player: the
        // front-angle geometry judgement rejects it before any IPC.
        knock(motion = Vec3(0.0, 0.4, 1.0))

        assertFalse(lastGatesOpen, "the 120 degree front cone is a module judgement")
        assertEquals(0, gateCalls, "geometry short-circuits the gate too")
    }

    @Test
    fun `a closed unified gate is reported to the core`() {
        gateOk = false
        knock()

        assertFalse(lastGatesOpen, "the gate's fire bit is marshalled, not re-decided")
        assertEquals(1, gateCalls, "the gate was still asked")
        assertEquals(0, jumps)
    }

    @Test
    fun `the cooldown mode is marshalled as a wire code`() {
        ConfigManager.set("JumpReset.CooldownMode", "Ticks")
        knock()
        assertEquals(0, lastCooldownMode, "Ticks maps to wire mode code 0 on knock")

        tick()
        assertEquals(0, lastCooldownMode, "Ticks maps to wire mode code 0 on pump")

        ConfigManager.set("JumpReset.CooldownMode", "ReceivedHits")
        tick()
        assertEquals(1, lastCooldownMode, "ReceivedHits maps to wire mode code 1 on pump")
    }

    // ---- Turning the core's step into the single platform write -------------

    @Test
    fun `a JUMP from knock queues exactly one jump and flashes`() {
        knockCode = JumpResetStep.JUMP.code

        knock()

        assertEquals(1, jumps, "one knock, one jump")
        assertTrue(JumpReset.flashStrength() > 0f, "the HUD flashes on the jump actually asked for")
    }

    @Test
    fun `a JUMP from pump queues exactly one jump`() {
        pumpCode = JumpResetStep.JUMP.code

        tick()

        assertEquals(1, jumps, "a delayed jump fires on the client tick")
    }

    @Test
    fun `NONE and a missing answer both queue nothing`() {
        knockCode = JumpResetStep.NONE.code
        pumpCode = JumpResetStep.NONE.code
        knock()
        tick()
        assertEquals(0, jumps, "NONE never jumps")

        knockCode = null // the core did not answer
        pumpCode = null
        knock()
        tick()
        assertEquals(0, jumps, "never invent a jump without the core's decision")
    }

    @Test
    fun `the final still-sprinting judgement stays in the module`() {
        // The core says JUMP, but by the time the platform would press the key
        // the player has stopped sprinting. The module owns this last check.
        pumpCode = JumpResetStep.JUMP.code
        tick(sprintingPlayer(sprinting = false))
        assertEquals(0, jumps, "a delayed jump must not fire off-sprint")

        knockCode = JumpResetStep.JUMP.code
        knock(sprintingPlayer(onGround = false))
        assertEquals(0, jumps, "a delayed jump must not fire airborne")
    }
}
