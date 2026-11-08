package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.ADTapSample
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.Strategies
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ADTap's adapter side after the chance roll and both range draws moved into
 * the core.
 *
 * The fire-point chance roll, the tap duration and the inter-tap gap live in
 * `core-rs::strategy::adtap_sample` and are matched row-for-row by
 * `ADTapSampleParityTest`. What stays here is exactly what the core cannot do:
 *  - the judgements (physical A/D and W keys, the ground guard, target guards,
 *    the pattern mode);
 *  - marshalling the configured values into one request;
 *  - turning the returned packed scalar into the claimLeft / claimRight and
 *    TAP_BEGIN platform writes;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor draws locally: there is
 * no `kotlin.random` left in the module.
 */
class ADTapSampleWiringTest {

    private lateinit var core: CoreStub
    private val begins = mutableListOf<Pair<Int, Boolean>>()          // durationMs to postDelay
    private val samples = mutableListOf<Pair<Int, List<Int>>>()       // kind to (chance, tapMin, tapMax, gapMin, gapMax)
    private var fireCode: Double? = ADTapSample.pack(true, 40, 0).toDouble()
    private var gapCode: Double? = ADTapSample.pack(false, 0, 70).toDouble()
    private var sevenSide = 0.0
    private var tickAnswer = 0.0

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        begins.clear()
        samples.clear()
        fireCode = ADTapSample.pack(true, 40, 0).toDouble()
        gapCode = ADTapSample.pack(false, 0, 70).toDouble()
        sevenSide = 0.0
        tickAnswer = 0.0

        core = mockk(relaxed = true)
        // IDLE, no state-machine event: only the fire point is exercised.
        every { core.tapTick(any(), any(), any(), any()) } answers { tickAnswer }
        every {
            core.adtapSample(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val kind = arg<Int>(2)
            samples.add(kind to listOf(arg<Int>(3), arg<Int>(4), arg<Int>(5), arg<Int>(6), arg<Int>(7)))
            if (kind == Strategies.ADTapSampleKind.FIRE) fireCode else gapCode
        }
        every { core.tapSeven(any(), any(), any(), any(), any()) } answers { sevenSide }
        every {
            core.tapBegin(any(), any(), any(), any(), any(), any())
        } answers {
            begins.add(arg<Int>(3) to arg<Boolean>(4))
            Unit
        }
        CoreLink.stub = core

        EventBridge.physicalForwardDown = true
        EventBridge.physicalLeftDown = false
        EventBridge.physicalRightDown = false
        EventBridge.crosshairTarget = target()

        ConfigManager.set("ADTap.RangeMin", 0f)
        ConfigManager.set("ADTap.RangeMax", 6f)
    }

    @AfterEach
    fun tearDown() {
        ADTap.disable()
        CoreLink.stub = null
        EventBridge.reset()
    }

    private fun target(distance: Float = 2f) = TargetState(
        entityId = 7,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = 20f,
        hurtTime = 5,
        distance = distance,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    /** ADTap's ground guard reads the player, so the tick player must stand on one. */
    private fun groundPlayer() = PlayerState.EMPTY.copy(onGround = true)

    private fun tick(t: TargetState? = target()) {
        EventBridge.crosshairTarget = t
        EventBridge.onTick(groundPlayer(), t)
    }

    // ---- Turning the core's packed answer into the platform writes ---------

    @Test
    fun `a passing fire claims a side and starts the tap with the core duration`() {
        ADTap.enable()
        tick()

        assertEquals(listOf(40 to false), begins, "the core's duration lands in TAP_BEGIN")
        assertTrue(
            samples.any { it.first == Strategies.ADTapSampleKind.FIRE },
            "the fire point asks ADTap's own stream for the roll and the draws"
        )
        assertTrue(EventBridge.isKeyLeftActive, "Seven side 0 is a LEFT claim")
    }

    @Test
    fun `a failed chance roll cools off for a gap instead`() {
        fireCode = ADTapSample.pack(false, 0, 90).toDouble()
        ADTap.enable()
        tick()

        assertEquals(listOf(90 to true), begins, "the fail path arms a gap post-delay, not a tap")
        assertFalse(EventBridge.isKeyLeftActive, "no key is claimed on a failed roll")
    }

    @Test
    fun `END_TAP releases the side and arms the gap without restarting`() {
        ADTap.enable()
        tick()                                    // fire: LEFT claimed, tap 40 ms
        assertTrue(EventBridge.isKeyLeftActive)

        // The tick answer an ended tap produces: event END_TAP, phase already
        // IDLE — the exact combination that used to restart the pattern and
        // overwrite the gap on the same tick.
        tickAnswer = 1.0
        tick()

        assertEquals(
            listOf(40 to false, 70 to true),
            begins,
            "the tap ends and the gap is armed; the gap must not be overwritten"
        )
        assertEquals(
            listOf(Strategies.ADTapSampleKind.FIRE, Strategies.ADTapSampleKind.GAP),
            samples.map { it.first },
            "exactly one fire and one gap draw — no same-tick restart"
        )
        assertFalse(EventBridge.isKeyLeftActive, "the tapped side is released")
    }

    @Test
    fun `a missing fire answer degrades to no tap`() {
        fireCode = null
        ADTap.enable()
        tick()

        assertTrue(begins.isEmpty(), "no core answer means no key claim, never a local guess")
        assertFalse(EventBridge.isKeyLeftActive)
    }

    @Test
    fun `a missing gap answer stops the pattern instead of guessing`() {
        ADTap.enable()
        tick()
        tickAnswer = 1.0
        gapCode = null
        tick()

        assertEquals(listOf(40 to false), begins, "the tap ended, but no gap is armed on a silent core")
    }

    @Test
    fun `the player's own strafing aborts the module`() {
        EventBridge.physicalLeftDown = true
        ADTap.enable()
        tick()

        assertTrue(begins.isEmpty(), "the player's fingers always win")
        assertTrue(samples.isEmpty(), "no draw may be spent fighting the player")
    }

    // ---- The configured values are marshalled, not sampled here -------------

    @Test
    fun `the chance and both range pairs reach the core unchanged`() {
        ADTap.enable()
        tick()

        val fireRow = samples.last { it.first == Strategies.ADTapSampleKind.FIRE }
        assertEquals(
            listOf(100, 70, 140, 40, 110),
            fireRow.second,
            "chance / tapMin / tapMax / gapMin / gapMax are marshalled verbatim"
        )
    }
}
