package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.CombatDecision
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.SprintResetStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SprintReset's adapter side.
 *
 * The stateful machine (rising edge, hit-count throttle, per-target cycle,
 * delay window and the chance roll) lives in `core-rs::strategy::sprint_reset`
 * and is matched tick-for-tick by `SprintResetParityTest`. What stays here is
 * exactly what the core cannot do:
 *  - the pure judgements (target present, distance window, unified gate, hurt);
 *  - marshalling those judgements and the tuning knobs into one request;
 *  - turning the returned [SprintResetStep] into the single platform write;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor sends a stray packet
 * burst: no `Random`, no `nanoTime()` deadline, no cross-tick counters.
 */
class SprintResetTest {

    private lateinit var core: CoreStub
    private val sent = mutableListOf<String>()
    private var gateOk = true
    private var stepCode: Int? = SprintResetStep.NONE.code
    private var lastModeCode = -1

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        sent.clear()
        gateOk = true
        stepCode = SprintResetStep.NONE.code
        lastModeCode = -1

        core = mockk(relaxed = true)
        // The unified gate goes through combatDecide; the adapter only reads
        // its `fire` bit and marshals it as an input, nothing more.
        every {
            core.combatDecide(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CombatDecision(gateOk, 0, 1) }
        every {
            core.playerSprintReset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            lastModeCode = arg(11)
            stepCode
        }
        CoreLink.stub = core

        EventBridge.registerSprintResetHandler { sent.add(it) }
        // Keep the tuning knobs at unambiguous values so the marshalled inputs
        // are easy to assert on.
        ConfigManager.set("SprintReset.Chance", 100)
        ConfigManager.set("SprintReset.Delay", 0)
        ConfigManager.set("SprintReset.HitCount", 1)
        ConfigManager.set("SprintReset.Mode", "Nostop")
        ConfigManager.set("SprintReset.RangeMin", 0f)
        ConfigManager.set("SprintReset.RangeMax", 6f)
        SprintReset.enable()
    }

    @AfterEach
    fun tearDown() {
        SprintReset.disable()
        CoreLink.stub = null
        EventBridge.reset()
        sent.clear()
    }

    private fun target(distance: Float = 2f, hurtTime: Int = 5, entityId: Int = 7) = TargetState(
        entityId = entityId,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = 20f,
        hurtTime = hurtTime,
        distance = distance,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    /**
     * One background tick followed by the render frame that drains the queued
     * sprint reset. `sendSprintReset` only queues; the platform sends on the
     * main thread via `drainPendingSprintReset`, so a test that skips the drain
     * would see no handler call at all.
     */
    private fun tick(t: TargetState? = target()) {
        EventBridge.onTick(PlayerState.EMPTY, t)
        EventBridge.drainPendingSprintReset()
    }

    // ---- The adapter marshals judgements, it does not decide ----------------

    @Test
    fun `judgements reach the core as inputs`() {
        var hasTarget = false
        var inRange = false
        var hurt = false
        var gate = false
        var entityId = -1
        every {
            core.playerSprintReset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            hasTarget = arg(3)
            inRange = arg(4)
            gate = arg(5)
            hurt = arg(6)
            entityId = arg(7)
            SprintResetStep.NONE.code
        }

        tick(target(distance = 2f, hurtTime = 5, entityId = 7))

        assertTrue(hasTarget, "target present reaches the core")
        assertTrue(inRange, "distance-window judgement reaches the core")
        assertTrue(gate, "unified-gate judgement reaches the core")
        assertTrue(hurt, "hurt bit reaches the core")
        assertEquals(7, entityId, "entity id is marshalled for the per-target cycle")
    }

    @Test
    fun `an out-of-range target is reported so the core can skip it`() {
        var inRange = true
        every {
            core.playerSprintReset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            inRange = arg(4)
            SprintResetStep.NONE.code
        }

        tick(target(distance = 9f))

        assertFalse(inRange, "the distance window is a judgement, evaluated here")
    }

    @Test
    fun `no target is still reported every tick`() {
        var calls = 0
        var hasTarget = true
        every {
            core.playerSprintReset(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            calls++
            hasTarget = arg(3)
            SprintResetStep.NONE.code
        }

        tick(null)

        assertEquals(1, calls, "every tick sends a request, even with no target")
        assertFalse(hasTarget, "the core needs the no-target input to forget the edge")
    }

    // ---- Turning the core's step into the single platform write -------------

    @Test
    fun `SEND_NOSTOP writes a Nostop reset and flashes`() {
        stepCode = SprintResetStep.SEND_NOSTOP.code

        tick()

        assertEquals(listOf("Nostop"), sent)
        assertTrue(SprintReset.flashStrength() > 0f, "the HUD flashes on the reset actually sent")
    }

    @Test
    fun `SEND_SILENT writes a Silent reset`() {
        stepCode = SprintResetStep.SEND_SILENT.code

        tick()

        assertEquals(listOf("Silent"), sent)
    }

    @Test
    fun `the configured mode is marshalled as a code`() {
        ConfigManager.set("SprintReset.Mode", "Silent")
        tick()
        assertEquals(1, lastModeCode, "Silent maps to wire mode code 1")

        ConfigManager.set("SprintReset.Mode", "Nostop")
        tick()
        assertEquals(0, lastModeCode, "Nostop maps to wire mode code 0")
    }

    @Test
    fun `NONE and a missing answer both send nothing`() {
        stepCode = SprintResetStep.NONE.code
        tick()
        assertTrue(sent.isEmpty(), "NONE never sends")

        stepCode = null // the core did not answer
        tick()
        assertTrue(sent.isEmpty(), "never invent a packet burst without the core's decision")
    }
}
