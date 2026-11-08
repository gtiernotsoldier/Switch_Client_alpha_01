package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.CombatDecision
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.Ops
import io.doppel.ipc.Strategies
import io.doppel.ipc.TapSample
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WTap / STap adapter side after the range draws moved into the core.
 *
 * The three uniform draws (post-delay, tap duration, initial hit threshold)
 * live in `core-rs::strategy::tap_sample` and are matched row-for-row by
 * `TapSampleParityTest`. What stays here is exactly what the core cannot do:
 *  - the judgements (physical keys, attack, target guards, the CombatTrigger verdict);
 *  - marshalling the configured ranges into one request;
 *  - turning the returned packed scalar into the one platform write;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor draws locally: there is
 * no `kotlin.random` left in either module.
 */
class TapSampleWiringTest {

    private lateinit var core: CoreStub
    private val begins = mutableListOf<Pair<Int, Boolean>>()   // durationMs to postDelay
    private val samples = mutableListOf<Pair<Int, Int>>()       // tapOp to kind
    private val sampleRanges = mutableListOf<List<Int>>()
    private var sampleCode: Double? = TapSample.pack(0, 40, 0).toDouble()
    private var decisionFire = true

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        begins.clear()
        samples.clear()
        sampleRanges.clear()
        sampleCode = TapSample.pack(0, 40, 0).toDouble()
        decisionFire = true

        core = mockk(relaxed = true)
        // IDLE, no state-machine event: only the trigger path is exercised.
        every { core.tapTick(any(), any(), any(), any()) } answers { 0.0 }
        every {
            core.combatDecide(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CombatDecision(decisionFire, 0, 1) }
        every {
            core.tapSample(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            samples.add(arg<Int>(0) to arg<Int>(3))
            sampleRanges.add(listOf(arg<Int>(4), arg<Int>(5), arg<Int>(6), arg<Int>(7), arg<Int>(8), arg<Int>(9)))
            sampleCode
        }
        every {
            core.tapBegin(any(), any(), any(), any(), any(), any())
        } answers {
            begins.add(arg<Int>(3) to arg<Boolean>(4))
            Unit
        }
        CoreLink.stub = core

        EventBridge.physicalForwardDown = true
        EventBridge.physicalBackDown = false
        EventBridge.syntheticAttack = true
        EventBridge.crosshairTarget = target()

        ConfigManager.set("WTap.RangeMin", 0f)
        ConfigManager.set("WTap.RangeMax", 6f)
        ConfigManager.set("STap.RangeMin", 0f)
        ConfigManager.set("STap.RangeMax", 6f)
    }

    @AfterEach
    fun tearDown() {
        WTap.disable()
        STap.disable()
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

    private fun tick(t: TargetState? = target()) {
        EventBridge.crosshairTarget = t
        EventBridge.onTick(PlayerState.EMPTY, t)
    }

    // ---- Turning the core's packed answer into the one platform write -------

    @Test
    fun `WTap fire with zero post-delay starts the tap with the core duration`() {
        WTap.enable()
        tick()

        assertEquals(listOf(40 to false), begins, "the core's duration lands in TAP_BEGIN")
        assertTrue(
            samples.any { it.first == Ops.TAP_WTAP_SAMPLE && it.second == Strategies.TapSampleKind.POST_DELAY_OR_START },
            "the fire point asks WTap's own stream for the draw"
        )
    }

    @Test
    fun `WTap fire with a post-delay arms a delayed start instead`() {
        sampleCode = TapSample.pack(30, 40, 0).toDouble()
        WTap.enable()
        tick()

        assertEquals(listOf(30 to true), begins, "post-delay arms TAP_POST_DELAY, not a tap")
    }

    @Test
    fun `a missing sample answer degrades to no tap`() {
        sampleCode = null
        WTap.enable()
        tick()

        assertTrue(begins.isEmpty(), "no core answer means no key press, never a local guess")
    }

    @Test
    fun `a no-fire verdict never asks for a draw`() {
        decisionFire = false
        WTap.enable()
        tick()

        assertTrue(begins.isEmpty(), "no fire, no platform write")
        assertTrue(
            samples.none { it.second == Strategies.TapSampleKind.POST_DELAY_OR_START },
            "the RNG stream must not advance on a no-fire tick"
        )
    }

    @Test
    fun `STap uses its own op and stream`() {
        STap.enable()
        tick()

        assertEquals(listOf(40 to false), begins)
        assertTrue(samples.isNotEmpty() && samples.all { it.first == Ops.TAP_STAP_SAMPLE },
            "every draw goes to STap's own stream")
    }

    // ---- The configured ranges are marshalled, not sampled here -------------

    @Test
    fun `the six range endpoints reach the core unchanged`() {
        WTap.enable()
        tick()

        val fireRow = sampleRanges.last()
        assertEquals(
            listOf(25, 55, 25, 55, 1, 1),
            fireRow,
            "postDelay / action / onceEvery min-max pairs are marshalled verbatim"
        )
    }
}
