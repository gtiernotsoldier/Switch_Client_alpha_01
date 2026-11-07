package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.PlayerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Eagle — Raven's SafeWalk sneak branch.
 *
 * The decision machine ([EagleLogic]) carries the reference behaviour and is tested exhaustively;
 * the module tests pin the wiring around it (which EventBridge calls the decisions turn into, the
 * HUD work flash, and the state released on disable).
 */
class EagleTest {

    // ================= Pure decision machine =================

    private fun input(
        onEdge: Boolean = false,
        settingsMet: Boolean = true,
        sneakKeyHeld: Boolean = false,
        autoSneakActive: Boolean = false,
        unsneakDelayTicks: Int = 0
    ) = EagleLogic.Input(onEdge, settingsMet, sneakKeyHeld, autoSneakActive, unsneakDelayTicks)

    @Test
    fun `a fresh edge presses sneak once and arms the hold delay`() {
        var rolls = 0
        val d = EagleLogic.tick(input(onEdge = true)) { rolls++; 3 }
        assertTrue(d.press, "the edge must press sneak")
        assertFalse(d.release)
        assertTrue(d.autoSneakActive, "the module now owns the sneak")
        assertEquals(3, d.unsneakDelayTicks)
        assertEquals(1, rolls, "the delay is rolled exactly once, when the press happens")
    }

    @Test
    fun `holding the edge keeps sneaking without pressing again`() {
        var rolls = 0
        val d = EagleLogic.tick(input(onEdge = true, autoSneakActive = true, unsneakDelayTicks = 3)) {
            rolls++
            1
        }
        assertFalse(d.press, "the key is already down")
        assertFalse(d.release)
        assertTrue(d.autoSneakActive)
        assertEquals(3, d.unsneakDelayTicks, "the delay is not re-armed while the edge holds")
        assertEquals(0, rolls)
    }

    @Test
    fun `leaving the edge keeps the sneak for the armed delay and only then releases`() {
        // Raven's unsneakDelayTicks countdown: delay-1 remaining ticks of hold, then the release.
        var d = EagleLogic.tick(input(autoSneakActive = true, unsneakDelayTicks = 2)) { 0 }
        assertFalse(d.release, "still inside the hold")
        assertEquals(1, d.unsneakDelayTicks)

        d = EagleLogic.tick(input(autoSneakActive = true, unsneakDelayTicks = 1)) { 0 }
        assertFalse(d.release, "last held tick")
        assertEquals(0, d.unsneakDelayTicks)

        d = EagleLogic.tick(input(autoSneakActive = true, unsneakDelayTicks = 0)) { 0 }
        assertTrue(d.release, "the hold is over")
        assertFalse(d.autoSneakActive)
    }

    @Test
    fun `a zero hold delay releases on the first tick off the edge`() {
        val d = EagleLogic.tick(input(autoSneakActive = true, unsneakDelayTicks = 0)) { 0 }
        assertTrue(d.release)
        assertFalse(d.autoSneakActive)
        assertEquals(0, d.unsneakDelayTicks)
    }

    @Test
    fun `an idle module does nothing off the edge`() {
        var rolls = 0
        val d = EagleLogic.tick(input()) { rolls++; 1 }
        assertFalse(d.press)
        assertFalse(d.release)
        assertFalse(d.autoSneakActive)
        assertEquals(0, rolls)
    }

    @Test
    fun `a player sneaking on their own is never fought`() {
        // Press: the player already holds the key, so the module does not press and does not take
        // ownership — which is what makes the later release a no-op for their key.
        val pressed = EagleLogic.tick(input(onEdge = true, sneakKeyHeld = true)) { 5 }
        assertFalse(pressed.press)
        assertFalse(pressed.autoSneakActive, "never claim a sneak we did not press")
        assertFalse(pressed.release)

        // And off the edge it still may not release anything.
        assertFalse(EagleLogic.tick(input(sneakKeyHeld = true)) { 0 }.release)
    }

    @Test
    fun `a held sneak key blocks the release until the player lets go`() {
        // Raven's `if (!sneakState && Utils.isBindDown(keyBindSneak)) return;`
        val blocked = EagleLogic.tick(
            input(sneakKeyHeld = true, autoSneakActive = true, unsneakDelayTicks = 0)
        ) { 0 }
        assertFalse(blocked.release, "releasing here would cancel the player's own sneak")
        assertTrue(blocked.autoSneakActive, "keep holding and retry")
        assertEquals(0, blocked.unsneakDelayTicks)

        val released = EagleLogic.tick(
            input(sneakKeyHeld = false, autoSneakActive = true, unsneakDelayTicks = 0)
        ) { 0 }
        assertTrue(released.release, "once the player let go, the module releases its own press")
    }

    @Test
    fun `failing the settings gate releases immediately and forgets the delay`() {
        val d = EagleLogic.tick(
            input(settingsMet = false, onEdge = true, autoSneakActive = true, unsneakDelayTicks = 4)
        ) { 9 }
        assertTrue(d.release)
        assertFalse(d.autoSneakActive)
        assertEquals(0, d.unsneakDelayTicks)
    }

    @Test
    fun `failing the settings gate still spares a key the player holds`() {
        val d = EagleLogic.tick(input(settingsMet = false, sneakKeyHeld = true, autoSneakActive = true)) { 0 }
        assertFalse(d.release)
        assertFalse(d.autoSneakActive, "we let go of ownership but not of their key")
    }

    // ================= Module wiring =================

    private var onEdge = false
    private var presses = 0
    private var releases = 0

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        presses = 0
        releases = 0
        onEdge = false
        EventBridge.registerEdgeDetector { onEdge }
        EventBridge.registerPressSneakHandler { presses++ }
        EventBridge.registerReleaseSneakHandler { releases++ }
        // Pin the hold delay so the module tests are deterministic (defaults are 1..5 random ticks).
        ConfigManager.set("Eagle.MaxSneakTimeMin", 1)
        ConfigManager.set("Eagle.MaxSneakTimeMax", 1)
        ConfigManager.set("Eagle.OnlyWhenLookingDown", false)
        ConfigManager.set("Eagle.LookDownThreshold", 45)
        Eagle.enable()
    }

    @AfterEach
    fun tearDown() {
        Eagle.disable()
        EventBridge.reset()
        // The option store is process-global: put Eagle's declared defaults back for other tests.
        ConfigManager.set("Eagle.MaxSneakTimeMin", 1)
        ConfigManager.set("Eagle.MaxSneakTimeMax", 5)
        ConfigManager.set("Eagle.OnlyWhenLookingDown", false)
        ConfigManager.set("Eagle.LookDownThreshold", 45)
    }

    private fun player(pitch: Float = 0f, sneaking: Boolean = false): PlayerState =
        PlayerState(
            "tester", 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, pitch,
            true, false, false, 20f, 0, 1L
        ).copy(isSneaking = sneaking)

    @Test
    fun `on the edge the module presses sneak and flashes the HUD`() {
        onEdge = true
        EventBridge.onTick(player(), null)

        assertEquals(1, presses)
        assertEquals(0, releases)
        assertTrue(Eagle.flashStrength() > 0f, "the HUD row flashes while the module is working")
    }

    @Test
    fun `leaving the edge releases the sneak one hold-tick later`() {
        onEdge = true
        EventBridge.onTick(player(), null)
        assertEquals(1, presses)

        onEdge = false
        EventBridge.onTick(player(), null) // hold tick (delay 1)
        assertEquals(0, releases, "the hold has not elapsed yet")

        EventBridge.onTick(player(), null) // delay spent -> release
        assertEquals(1, releases)
    }

    @Test
    fun `disabling the module gives the sneak key back`() {
        onEdge = true
        EventBridge.onTick(player(), null)
        assertEquals(1, presses)

        Eagle.disable()
        assertEquals(1, releases, "a disabled Eagle must not leave the player stuck sneaking")
    }

    @Test
    fun `a manual sneak is never pressed over nor released`() {
        onEdge = true
        EventBridge.onTick(player(sneaking = true), null)
        assertEquals(0, presses, "the player is already sneaking")

        Eagle.disable()
        assertEquals(0, releases, "the module never owned that key")
    }

    @Test
    fun `the pitch gate keeps the sneak key up when the player looks forward`() {
        ConfigManager.set("Eagle.OnlyWhenLookingDown", true)
        onEdge = true

        EventBridge.onTick(player(pitch = 0f), null)
        assertEquals(0, presses, "looking straight ahead fails the gate")

        EventBridge.onTick(player(pitch = 60f), null)
        assertEquals(1, presses, "looking down past the threshold engages")
    }
}
