package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.PlayerState
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.EaglePlaceStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Eagle AutoPlace's adapter side.
 *
 * The timing machine (aim-in-flight, wait ticks, cooldown and its random draw)
 * lives in `core-rs::strategy::eagle_place` and is matched tick-for-tick by
 * `EaglePlaceParityTest`. What stays here is exactly what the core cannot do:
 *  - the pure judgements ([Eagle.shouldAutoPlace] and [Eagle.pitchInTolerance]);
 *  - marshalling those judgements into one request;
 *  - turning the returned [EaglePlaceStep] into platform writes;
 *  - releasing a held right-click, and doing nothing when the core is silent.
 *
 * The two user reports behind this module: placements kept happening without
 * the downward aim, and AutoPlace fought BridgeAssist for the camera. Both are
 * now decided in the core; these tests prove the adapter neither re-decides nor
 * writes a stray block.
 */
class EagleAutoPlaceTest {

    private lateinit var core: CoreStub
    private var onEdge = true
    private var rotations = 0
    private var lastPitch: Float? = null
    private var placeStepCode: Int? = EaglePlaceStep.NONE.code

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        onEdge = true
        rotations = 0
        lastPitch = null
        placeStepCode = EaglePlaceStep.NONE.code

        core = mockk(relaxed = true)
        // The sneak half shares the same stub; an all-clear answer keeps it from
        // pressing/releasing keys that would pollute the placement assertions.
        every { core.playerEagle(any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        every {
            core.playerEaglePlace(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { placeStepCode }
        CoreLink.stub = core

        EventBridge.registerEdgeDetector { onEdge }
        EventBridge.registerRotationApplier { _, pitch -> rotations++; lastPitch = pitch }
        ConfigManager.set("Eagle.AutoPlace", true)
        ConfigManager.set("Eagle.PlacePitch", 78)
        ConfigManager.set("Eagle.PlaceTolerance", 6)
        ConfigManager.set("Eagle.PlaceTicksMin", 1)
        ConfigManager.set("Eagle.PlaceTicksMax", 1)
        Eagle.enable()
    }

    @AfterEach
    fun tearDown() {
        Eagle.disable()
        CoreLink.stub = null
        EventBridge.reset()
        // The option store is process-global: restore Eagle's declared defaults.
        ConfigManager.set("Eagle.AutoPlace", false)
        ConfigManager.set("Eagle.PlacePitch", 78)
        ConfigManager.set("Eagle.PlaceTolerance", 6)
        ConfigManager.set("Eagle.PlaceTicksMin", 1)
        ConfigManager.set("Eagle.PlaceTicksMax", 3)
    }

    private fun player(pitch: Float = 0f, sneaking: Boolean = true): PlayerState =
        PlayerState(
            "tester", 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, pitch,
            true, false, false, 20f, 0, 1L
        ).copy(isSneaking = sneaking)

    // ---- Judgements that stay in the module --------------------------------

    @Test
    fun `it fires only with both gates open`() {
        assertTrue(Eagle.shouldAutoPlace(true, true, onEdge = true, sneaking = true))
        assertFalse(Eagle.shouldAutoPlace(true, true, onEdge = false, sneaking = true), "no edge")
        assertFalse(Eagle.shouldAutoPlace(true, true, onEdge = true, sneaking = false), "not sneaking")
        assertFalse(Eagle.shouldAutoPlace(true, false, onEdge = true, sneaking = true), "option off")
        assertFalse(Eagle.shouldAutoPlace(false, true, onEdge = true, sneaking = true), "module off")
    }

    @Test
    fun `pitch tolerance is a pure geometry judgement`() {
        assertTrue(Eagle.pitchInTolerance(78f, 78f, 6f), "already at the angle")
        assertTrue(Eagle.pitchInTolerance(74f, 78f, 6f), "inside tolerance")
        assertFalse(Eagle.pitchInTolerance(20f, 78f, 6f), "looking at the horizon")
    }

    // ---- The adapter marshals judgements, it does not decide ---------------

    @Test
    fun `the module marshals its judgements and the core decides the step`() {
        var gatesSeen = false
        var pitchSeen = false
        every {
            core.playerEaglePlace(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            gatesSeen = arg(2)
            pitchSeen = arg(3)
            EaglePlaceStep.AIM.code
        }

        EventBridge.onTick(player(pitch = 78f), null)

        assertTrue(gatesSeen, "gate judgement reaches the core")
        assertTrue(pitchSeen, "pitch-tolerance judgement reaches the core")
    }

    @Test
    fun `a closed gate is still reported so the core can cancel its pending aim`() {
        var calls = 0
        var gatesSeen = true
        every {
            core.playerEaglePlace(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            calls++
            gatesSeen = arg(2)
            EaglePlaceStep.NONE.code
        }
        onEdge = false

        EventBridge.onTick(player(pitch = 78f), null)

        assertEquals(1, calls, "every tick sends a request, even with the gates closed")
        assertFalse(gatesSeen, "the closed gate is the input the core needs")
        assertFalse(EventBridge.syntheticUse, "no click on a closed gate")
    }

    // ---- Turning the core's step into platform writes ----------------------

    @Test
    fun `AIM writes the bridging angle and does not click`() {
        placeStepCode = EaglePlaceStep.AIM.code

        EventBridge.onTick(player(), null)

        assertEquals(1, rotations)
        assertEquals(78f, lastPitch!!, "the view is asked to look down at PlacePitch")
        assertFalse(EventBridge.syntheticUse, "AIM never places")
    }

    @Test
    fun `CLICK presses syntheticUse and flashes, then releases it on the next tick`() {
        placeStepCode = EaglePlaceStep.CLICK.code

        EventBridge.onTick(player(), null)
        assertTrue(EventBridge.syntheticUse, "the right-click is held for this tick")
        assertTrue(Eagle.flashStrength() > 0f, "the HUD flashes on the placement")

        // syntheticUse is a held button, OR-ed into the right mouse every frame;
        // the next tick must release only the press AutoPlace owns.
        placeStepCode = EaglePlaceStep.NONE.code
        EventBridge.onTick(player(), null)
        assertFalse(EventBridge.syntheticUse, "a placement lasts exactly one tick")
    }

    @Test
    fun `ABORT writes neither a rotation nor a click`() {
        placeStepCode = EaglePlaceStep.ABORT.code

        EventBridge.onTick(player(), null)

        assertEquals(0, rotations)
        assertFalse(EventBridge.syntheticUse)
    }

    @Test
    fun `a missing answer degrades to no rotation and no click`() {
        placeStepCode = null // the core did not answer

        EventBridge.onTick(player(), null)

        assertEquals(0, rotations, "no aim without an answer")
        assertFalse(EventBridge.syntheticUse, "never place a block without the core's decision")
    }
}
