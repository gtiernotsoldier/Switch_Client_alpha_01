package io.doppel.adapter.common.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A1 — the Keystrokes HUD reads *effective* key states, not the physical ones.
 *
 * Reported bug: after the sticky-key fix the boxes went dead for anything a module did, because
 * [EventBridge.isKeyForwardDown] and friends only carry the player's physical keys. WTap presses /
 * releases W, STap presses S, ADTap taps A/D and JumpReset pulses space — all synthetically — so the
 * HUD has to OR those in without ever reading `KeyBinding.pressed` back (that latch is what made
 * the keys stick in the first place).
 *
 * These tests are the contract the Keystrokes renderer now depends on.
 */
class EventBridgeEffectiveKeysTest {

    @BeforeEach
    fun setUp() = EventBridge.reset()

    @AfterEach
    fun tearDown() = EventBridge.reset()

    @Test
    fun `a synthetic W press lights the box while the physical key is up`() {
        EventBridge.isKeyForwardDown = false // player is not touching W
        EventBridge.syntheticForward = true // WTap is holding it
        EventBridge.syntheticForwardOverride = true

        assertTrue(EventBridge.isKeyForwardActive, "module-driven W must light the keystroke box")
    }

    @Test
    fun `a plain physical W press still lights the box with no module active`() {
        EventBridge.isKeyForwardDown = true
        EventBridge.syntheticForward = false
        EventBridge.syntheticForwardOverride = false

        assertTrue(EventBridge.isKeyForwardActive)
    }

    @Test
    fun `a W-tap reads as released even though the player still holds W`() {
        // This is the whole point of the override: while WTap owns the key, the game sees only
        // syntheticForward. Physical W being held must NOT keep the box lit through the tap.
        EventBridge.isKeyForwardDown = true
        EventBridge.syntheticForward = false
        EventBridge.syntheticForwardOverride = true

        assertFalse(EventBridge.isKeyForwardActive, "an active W-tap is a W release")
    }

    @Test
    fun `an STap S press lights the box while the physical key is up`() {
        EventBridge.isKeyBackDown = false
        EventBridge.syntheticBack = true
        EventBridge.syntheticBackOverride = true

        assertTrue(EventBridge.isKeyBackActive)
    }

    @Test
    fun `ADTap strafe always ORs with the player's own key`() {
        // ADTap has no override by design — it must never steal the player's strafing.
        EventBridge.syntheticLeft = true
        EventBridge.isKeyLeftDown = false
        assertTrue(EventBridge.isKeyLeftActive, "synthetic A alone lights A")

        EventBridge.syntheticLeft = false
        EventBridge.isKeyLeftDown = true
        assertTrue(EventBridge.isKeyLeftActive, "player's own A alone lights A")

        EventBridge.syntheticLeft = false
        EventBridge.isKeyLeftDown = false
        assertFalse(EventBridge.isKeyLeftActive, "neither source → dark")

        EventBridge.syntheticRight = true
        assertTrue(EventBridge.isKeyRightActive, "synthetic D alone lights D")
    }

    @Test
    fun `a JumpReset pulse lights space while the physical key is up`() {
        EventBridge.isKeyJumpDown = false
        EventBridge.queueJump()
        assertTrue(EventBridge.drainPendingJump(), "the queued pulse presses the jump key")

        assertTrue(EventBridge.isJumpPulseActive())
        assertTrue(EventBridge.isKeyJumpActive, "JumpReset's space press must light the box")
    }

    @Test
    fun `everything reads dark when nothing presses the keys`() {
        assertFalse(EventBridge.isKeyForwardActive)
        assertFalse(EventBridge.isKeyBackActive)
        assertFalse(EventBridge.isKeyLeftActive)
        assertFalse(EventBridge.isKeyRightActive)
        assertFalse(EventBridge.isKeyJumpActive)
    }

    @Test
    fun `disabling a tap module releases its synthetic key`() {
        // WTap/STap/ADTap clear their flags in onDisable; the effective accessors must follow, or a
        // disabled module would leave a box lit (the display version of a sticky key).
        EventBridge.syntheticForward = true
        EventBridge.syntheticForwardOverride = true
        assertTrue(EventBridge.isKeyForwardActive)

        EventBridge.syntheticForward = false
        EventBridge.syntheticForwardOverride = false
        assertFalse(EventBridge.isKeyForwardActive)
    }
}
