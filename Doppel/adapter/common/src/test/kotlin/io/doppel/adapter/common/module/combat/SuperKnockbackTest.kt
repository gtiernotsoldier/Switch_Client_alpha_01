package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.CombatDecision
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.SuperKnockbackFireStep
import io.doppel.ipc.SuperKnockbackSprintStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SuperKnockback's adapter side.
 *
 * The phase machine (Idle / Delay / SprintTap), the delay deadline, the
 * coordination offset, the SprintTap force sequence, the per-target hit-count
 * throttle and the chance roll live in `core-rs::strategy::super_knockback`
 * and are matched tick-for-tick by `SuperKnockbackParityTest`. What stays here
 * is exactly what the core cannot do:
 *  - the pure judgements (crosshair target, trigger style, distance window,
 *    unified gate, server sprint mirror, `isMovingForward`, coordination);
 *  - marshalling those judgements and the tuning knobs into one request;
 *  - turning the two packed step codes into the platform writes;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor fires a stray packet:
 * no `Random`, no local deadline, no cross-tick decision state.
 */
class SuperKnockbackTest {

    private lateinit var core: CoreStub
    private val actions = mutableListOf<String>()
    private var gateOk = true
    private var gateCalls = 0
    private var stepCode: Int? = 0
    private var hasTarget = false
    private var triggerOk = false
    private var inRange = false
    private var marshalledGateOk = false
    private var entityId = -1
    private var modeCode = -1
    private var sprintTapAllowed = false
    private var coordinationActive = false

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        actions.clear()
        gateOk = true
        gateCalls = 0
        stepCode = 0
        hasTarget = false
        triggerOk = false
        inRange = false
        marshalledGateOk = false
        entityId = -1
        modeCode = -1
        sprintTapAllowed = false
        coordinationActive = false

        core = mockk(relaxed = true)
        every {
            core.combatDecide(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { gateCalls++; CombatDecision(gateOk, 0, 1) }
        // playerSuperKnockback: player, target, nowNs, hasTarget, triggerOk,
        // inRange, gateOk, entityId, chance, hitCount, delayMs, mode,
        // sprintTapAllowed, coordinationActive, tick
        every {
            core.playerSuperKnockback(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            hasTarget = arg(3)
            triggerOk = arg(4)
            inRange = arg(5)
            marshalledGateOk = arg(6)
            entityId = arg(7)
            modeCode = arg(11)
            sprintTapAllowed = arg(12)
            coordinationActive = arg(13)
            stepCode
        }
        CoreLink.stub = core

        EventBridge.registerEntityActionHandler { actions.add(it) }
        EventBridge.crosshairTarget = target()
        ConfigManager.set("SuperKnockback.Mode", "Old")
        ConfigManager.set("SuperKnockback.Chance", 100)
        ConfigManager.set("SuperKnockback.Delay", 0)
        ConfigManager.set("SuperKnockback.HitCount", 1)
        ConfigManager.set("SuperKnockback.RangeMin", 0f)
        ConfigManager.set("SuperKnockback.RangeMax", 6f)
        ConfigManager.set("SuperKnockback.TriggerMode", "RisingEdge")
        ConfigManager.set("SuperKnockback.HurtTime", 10)
        SuperKnockback.enable()
    }

    @AfterEach
    fun tearDown() {
        SuperKnockback.disable()
        CoreLink.stub = null
        EventBridge.crosshairTarget = null
        EventBridge.reset()
        actions.clear()
        // The option store is process-global: restore declared defaults.
        ConfigManager.set("SuperKnockback.Mode", "SprintTap")
        ConfigManager.set("SuperKnockback.Chance", 100)
        ConfigManager.set("SuperKnockback.Delay", 0)
        ConfigManager.set("SuperKnockback.HitCount", 1)
        ConfigManager.set("SuperKnockback.RangeMin", 0f)
        ConfigManager.set("SuperKnockback.RangeMax", 6f)
        ConfigManager.set("SuperKnockback.TriggerMode", "RisingEdge")
        ConfigManager.set("SuperKnockback.HurtTime", 10)
    }

    private fun player(
        sprinting: Boolean = true,
        movingForward: Boolean = true
    ) = PlayerState(
        "tester", 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, 0f,
        true, movingForward, sprinting, 20f, 0, 1L
    )

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

    /** Low nibble = SprintTap step, high nibble = fire step. */
    private fun code(sprint: Int = 0, fire: Int = 0) = sprint or (fire shl 4)

    private fun tick(attack: Boolean = true, p: PlayerState = player()) {
        if (attack) EventBridge.notifyAttack(target())
        EventBridge.onTick(p, target())
    }

    // ---- The adapter marshals judgements, it does not decide ----------------

    @Test
    fun `judgements reach the core as inputs`() {
        tick()

        assertTrue(hasTarget, "crosshair target present reaches the core")
        assertTrue(triggerOk, "the one-tick attack pulse reaches the core")
        assertTrue(inRange, "the distance window is a judgement, evaluated here")
        assertTrue(marshalledGateOk, "the unified gate is folded in")
        assertEquals(7, entityId, "entity id is marshalled for the per-target throttle")
        assertEquals(1, modeCode, "Old maps to wire mode code 1")
    }

    @Test
    fun `an out-of-range target is reported and short-circuits the gate`() {
        EventBridge.crosshairTarget = target(distance = 9f)
        tick()

        assertFalse(inRange, "out of range is a judgement, not a core decision")
        assertFalse(marshalledGateOk, "gateOk is false when the range fails")
        assertEquals(0, gateCalls, "a failed range never asks the unified gate")
    }

    @Test
    fun `no target is still reported every tick`() {
        EventBridge.crosshairTarget = null
        tick(attack = false)

        assertFalse(hasTarget, "the core needs the no-target input")
        assertFalse(inRange, "no target means the range judgement is false")
        assertFalse(marshalledGateOk, "no target means the gate answer is false")
        assertEquals(0, gateCalls, "a missing target short-circuits the unified gate")
    }

    @Test
    fun `no attack pulse means no RisingEdge trigger`() {
        tick(attack = false)

        assertFalse(triggerOk, "a quiet tick does not fire")
    }

    @Test
    fun `HurtTimeExact compares the configured value on the JVM`() {
        ConfigManager.set("SuperKnockback.TriggerMode", "HurtTimeExact")
        ConfigManager.set("SuperKnockback.HurtTime", 5)
        EventBridge.crosshairTarget = target(hurtTime = 5)
        tick(attack = false)
        assertTrue(triggerOk, "an exact hurtTime match is a module judgement")

        EventBridge.crosshairTarget = target(hurtTime = 4)
        tick(attack = false)
        assertFalse(triggerOk, "a mismatched hurtTime closes the trigger")
    }

    @Test
    fun `the server sprint mirror and the coordination flag are marshalled`() {
        EventBridge.reportServerSprintState(true)
        EventBridge.setSprintResetActive(true)
        tick()

        assertTrue(sprintTapAllowed, "player.isSprinting && serverSprintState reaches the core")
        assertTrue(coordinationActive, "both sprint modules active reaches the core")
    }

    @Test
    fun `the mode is marshalled as a wire code`() {
        ConfigManager.set("SuperKnockback.Mode", "SprintTap")
        tick()
        assertEquals(0, modeCode, "SprintTap maps to wire mode code 0")

        ConfigManager.set("SuperKnockback.Mode", "SneakPacket")
        tick()
        assertEquals(2, modeCode, "SneakPacket maps to wire mode code 2")
    }

    // ---- Turning the two packed steps into platform writes -----------------

    @Test
    fun `an OLD fire writes the C0B burst and forces sprint`() {
        stepCode = code(fire = SuperKnockbackFireStep.OLD.code)

        tick()

        assertEquals(
            listOf("STOP_SPRINTING", "START_SPRINTING", "STOP_SPRINTING", "START_SPRINTING"),
            actions,
            "the sprinting player gets the extra STOP first"
        )
        assertTrue(EventBridge.hasSprintClaim("SuperKnockback"), "Old forces an OWNED sprint claim")
        assertTrue(SuperKnockback.flashStrength() > 0f, "the HUD flashes on the hit actually fired")
    }

    @Test
    fun `a SNEAK_PACKET fire writes four interleaved packets`() {
        stepCode = code(fire = 6)

        tick()

        assertEquals(
            listOf("STOP_SPRINTING", "START_SNEAKING", "START_SPRINTING", "STOP_SNEAKING"),
            actions
        )
    }

    @Test
    fun `a SPRINT_TAP fire flashes without sending packets`() {
        stepCode = code(fire = 4)

        tick()

        assertTrue(actions.isEmpty(), "SprintTap writes no C0B packets on the fire tick")
        assertTrue(SuperKnockback.flashStrength() > 0f)
    }

    @Test
    fun `the three SprintTap steps drive the force sequence`() {
        stepCode = code(sprint = SuperKnockbackSprintStep.BREAK.code)
        tick()
        assertTrue(EventBridge.hasSprintClaim("SuperKnockback"), "BREAK claims sprint=false")

        stepCode = code(sprint = SuperKnockbackSprintStep.RESTORE.code)
        tick(p = player(movingForward = true))
        assertTrue(EventBridge.hasSprintClaim("SuperKnockback"), "RESTORE claims sprint=true")

        stepCode = code(sprint = SuperKnockbackSprintStep.END.code)
        tick()
        assertFalse(EventBridge.hasSprintClaim("SuperKnockback"), "END releases the OWNED claim")
    }

    @Test
    fun `RESTORE leaves the claim alone when the player stopped moving forward`() {
        stepCode = code(sprint = SuperKnockbackSprintStep.RESTORE.code)
        tick(p = player(movingForward = false))

        assertFalse(EventBridge.hasSprintClaim("SuperKnockback"), "no restore without forward motion")
    }

    @Test
    fun `a packed answer lands both platform writes in one tick`() {
        // SprintTap BREAK at the top of the tick, an OLD fire later in the same tick.
        stepCode = code(sprint = SuperKnockbackSprintStep.BREAK.code, fire = 5)

        tick()

        assertTrue(EventBridge.hasSprintClaim("SuperKnockback"), "the force step landed")
        assertEquals(4, actions.size, "the fire step landed too")
    }

    @Test
    fun `NONE and a missing answer write nothing`() {
        stepCode = 0
        tick()
        assertTrue(actions.isEmpty())
        assertFalse(EventBridge.hasSprintClaim("SuperKnockback"))

        stepCode = null // the core did not answer
        tick()
        assertTrue(actions.isEmpty(), "never invent a C0B burst without the core's decision")
        assertFalse(EventBridge.hasSprintClaim("SuperKnockback"))
    }
}
