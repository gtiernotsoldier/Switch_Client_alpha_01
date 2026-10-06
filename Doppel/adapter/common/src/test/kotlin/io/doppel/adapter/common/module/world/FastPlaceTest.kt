package io.doppel.adapter.common.module.world

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.core.model.PlayerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * FastPlace — Raven's right-click delay override.
 *
 * [FastPlaceLogic] is the pure decision (tested exhaustively); the module tests pin what the module
 * actually writes through `EventBridge.setRightClickDelay`, including the activation-time gate and
 * the streak reset when the right click stops.
 */
class FastPlaceTest {

    // ================= Pure decision =================

    @Test
    fun `a passing gate writes the configured tick delay`() {
        for (delay in 0..3) {
            assertEquals(
                delay,
                FastPlaceLogic.delayToApply(activationElapsed = true, pitchOk = true, tickDelay = delay),
                "tick delay $delay must reach the timer"
            )
        }
    }

    @Test
    fun `zero means instant placement, not off`() {
        // The old Doppel module treated 0 as "disabled"; Raven writes 0 straight into the timer.
        assertEquals(0, FastPlaceLogic.delayToApply(true, true, 0))
    }

    @Test
    fun `the activation time holds the override back`() {
        assertNull(FastPlaceLogic.delayToApply(activationElapsed = false, pitchOk = true, tickDelay = 1))
    }

    @Test
    fun `the pitch gate holds the override back`() {
        assertNull(FastPlaceLogic.delayToApply(activationElapsed = true, pitchOk = false, tickDelay = 1))
    }

    @Test
    fun `four and out-of-range delays are never written`() {
        // Raven: `if (delay == 4) return;` — 4 is vanilla's timer value, "no override".
        assertNull(FastPlaceLogic.delayToApply(true, true, FastPlaceLogic.VANILLA_MAX_DELAY))
        assertNull(FastPlaceLogic.delayToApply(true, true, 5))
        assertNull(FastPlaceLogic.delayToApply(true, true, -1))
    }

    @Test
    fun `raven bounds are preserved`() {
        assertEquals(70f, FastPlaceLogic.PITCH_MIN)
        assertEquals(0, FastPlaceLogic.TICK_DELAY_MIN)
        assertEquals(3, FastPlaceLogic.TICK_DELAY_MAX)
        assertEquals(4, FastPlaceLogic.VANILLA_MAX_DELAY)
    }

    // ================= Module wiring =================

    private val written = mutableListOf<Int>()

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        written.clear()
        EventBridge.registerRightClickDelayHandler { written.add(it) }
        ConfigManager.set("FastPlace.TickDelay", 1)
        ConfigManager.set("FastPlace.ActivationTime", 0)
        ConfigManager.set("FastPlace.PitchCheck", false)
        EventBridge.isRightMousePhysicallyDown = false
        EventBridge.syntheticUse = false
        EventBridge.isGuiOpen = false
        FastPlace.enable()
    }

    @AfterEach
    fun tearDown() {
        FastPlace.disable()
        EventBridge.reset()
        // The option store is process-global: leave the reference defaults behind for every other test.
        ConfigManager.set("FastPlace.TickDelay", 1)
        ConfigManager.set("FastPlace.ActivationTime", 0)
        ConfigManager.set("FastPlace.PitchCheck", false)
    }

    private fun player(pitch: Float = 0f, usingItem: Boolean = false): PlayerState =
        PlayerState(
            "tester", 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, pitch,
            true, false, false, 20f, 0, 1L
        ).copy(isUsingItem = usingItem)

    @Test
    fun `holding the use key writes the delay and flashes once`() {
        ConfigManager.set("FastPlace.TickDelay", 2)
        EventBridge.isRightMousePhysicallyDown = true

        EventBridge.onTick(player(), null)
        assertEquals(listOf(2), written)
        assertTrue(FastPlace.flashStrength() > 0f, "the HUD row flashes when fast placing engages")

        // Steady state: the timer is kept at 2, but the row does not flash again.
        val flashAfterFirst = FastPlace.flashStrength()
        EventBridge.onTick(player(), null)
        assertEquals(listOf(2, 2), written)
        assertTrue(FastPlace.flashStrength() <= flashAfterFirst)
    }

    @Test
    fun `zero ticks is instant placement`() {
        ConfigManager.set("FastPlace.TickDelay", 0)
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.onTick(player(), null)
        assertEquals(listOf(0), written)
    }

    @Test
    fun `a synthetic right click (AutoBlock) counts as active`() {
        EventBridge.syntheticUse = true
        EventBridge.onTick(player(), null)
        assertEquals(listOf(1), written)
    }

    @Test
    fun `using an item counts as an active right click`() {
        EventBridge.onTick(player(usingItem = true), null)
        assertEquals(listOf(1), written)
    }

    @Test
    fun `an idle right click writes nothing`() {
        EventBridge.onTick(player(), null)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `the activation time suppresses the first ticks of a streak`() {
        ConfigManager.set("FastPlace.ActivationTime", 100)
        EventBridge.isRightMousePhysicallyDown = true

        EventBridge.onTick(player(), null)
        assertTrue(written.isEmpty(), "the activation time has not elapsed yet")
    }

    @Test
    fun `the activation time is measured from the start of the streak`() {
        ConfigManager.set("FastPlace.ActivationTime", 100)
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.onTick(player(), null) // starts the streak
        assertTrue(written.isEmpty())

        Thread.sleep(120)
        EventBridge.onTick(player(), null)
        assertEquals(listOf(1), written, "the activation time has now elapsed")
    }

    @Test
    fun `releasing the use key restarts the activation window`() {
        ConfigManager.set("FastPlace.ActivationTime", 100)
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.onTick(player(), null)

        // The player lets go: the streak is forgotten...
        EventBridge.isRightMousePhysicallyDown = false
        EventBridge.onTick(player(), null)

        // ...so the next press starts a fresh activation window and is held back again.
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.onTick(player(), null)
        assertTrue(written.isEmpty(), "a new streak must wait out the activation time again")
    }

    @Test
    fun `the pitch check skips a forward-facing crosshair`() {
        ConfigManager.set("FastPlace.PitchCheck", true)
        EventBridge.isRightMousePhysicallyDown = true

        EventBridge.onTick(player(pitch = 50f), null)
        assertTrue(written.isEmpty(), "50° < 70°: Raven's pitch gate rejects it")

        EventBridge.onTick(player(pitch = 80f), null)
        assertEquals(listOf(1), written)
    }

    @Test
    fun `an open GUI writes nothing`() {
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.isGuiOpen = true
        EventBridge.onTick(player(), null)
        assertTrue(written.isEmpty(), "the game does not have focus")
    }

    @Test
    fun `disabling stops the override`() {
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.onTick(player(), null)
        written.clear()

        FastPlace.disable()
        EventBridge.onTick(player(), null)
        assertTrue(written.isEmpty())
    }
}
