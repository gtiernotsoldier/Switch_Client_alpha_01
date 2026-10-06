package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.movement.InvMove
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.core.model.PlayerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The render/GUI hook contract the platform reads every frame.
 *
 * Two things are being pinned down here:
 *  1. every contract flag defaults to its inert value, so a platform that reads them before any
 *     module ran (or after `EventBridge.reset()`) writes nothing;
 *  2. the modules flip exactly their own flag and clear it again on disable — a flag left set would
 *     outlive the module and keep the platform writing to the renderer/Keyboard forever.
 *
 * The regression that started this: NoHurtCam must not drive the legacy per-tick handler, because
 * that one wrote `hurtTime` — the "just got hit" signal HitSelect/Velocity read, and the driver of
 * the red damage tint.
 *
 * NoFOV is gone (deleted at the user's request), so the FOV half of this contract no longer exists.
 */
class RenderOverrideContractTest {

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        disableModules()
    }

    @AfterEach
    fun tearDown() {
        disableModules()
        EventBridge.reset()
    }

    private fun disableModules() {
        NoHurtCam.disable()
        InvMove.disable()
    }

    @Test
    fun `the contract is inert by default`() {
        assertFalse(EventBridge.suppressHurtCam)
        assertFalse(EventBridge.keepMovementInGui)
        assertFalse(EventBridge.guiMovementKeepSprint)
        assertFalse(EventBridge.guiMovementOnlyInventory)
        assertNull(EventBridge.cameraYawOverride, "no freelook camera override without a module")
        assertNull(EventBridge.cameraPitchOverride)
    }

    @Test
    fun `NoHurtCam publishes the tilt cancellation and nothing else`() {
        NoHurtCam.enable()
        assertTrue(EventBridge.suppressHurtCam)
        assertFalse(EventBridge.keepMovementInGui)

        NoHurtCam.disable()
        assertFalse(EventBridge.suppressHurtCam, "disable must clear the flag — no leftover hurt-cam state")
    }

    @Test
    fun `NoHurtCam does not touch hurtTime or the legacy per-tick handler`() {
        var legacyCalls = 0
        EventBridge.registerResetHurtCamHandler { legacyCalls++ }
        try {
            NoHurtCam.enable()
            // Two ticks of the 20 Hz dispatcher: the old implementation called resetHurtCam() here,
            // and every call zeroed the player's hurtTime.
            EventBridge.onTick(PlayerState.EMPTY, null)
            EventBridge.onTick(PlayerState.EMPTY, null)
            org.junit.jupiter.api.Assertions.assertEquals(
                0, legacyCalls, "the module must not write hurtTime on the tick"
            )
        } finally {
            EventBridge.registerResetHurtCamHandler {} // leave no handler from this test behind
        }
    }

    @Test
    fun `InvMove asks for the key re-application only while it is on`() {
        InvMove.enable()
        assertTrue(EventBridge.keepMovementInGui, "the platform re-applies the movement keys")
        // Option defaults travel with the flag (KeepSprint on, OnlyInventory off).
        assertTrue(EventBridge.guiMovementKeepSprint)
        assertFalse(EventBridge.guiMovementOnlyInventory)

        InvMove.disable()
        assertFalse(EventBridge.keepMovementInGui)
        assertFalse(EventBridge.guiMovementKeepSprint)
        assertFalse(EventBridge.guiMovementOnlyInventory)
    }

    @Test
    fun `an option changed at runtime reaches the platform on the next tick`() {
        InvMove.enable()
        try {
            ConfigManager.set("InvMove.OnlyInventory", true)
            ConfigManager.set("InvMove.KeepSprint", false)
            // The WebUI writes the option; the module mirrors it on its tick (a one-off publish on
            // enable would keep the old value until the module was re-enabled).
            EventBridge.onTick(PlayerState.EMPTY, null)

            assertTrue(EventBridge.guiMovementOnlyInventory)
            assertFalse(EventBridge.guiMovementKeepSprint)
        } finally {
            ConfigManager.set("InvMove.OnlyInventory", false)
            ConfigManager.set("InvMove.KeepSprint", true)
            InvMove.disable()
        }
    }

    @Test
    fun `EventBridge reset clears every contract flag`() {
        NoHurtCam.enable()
        InvMove.enable()
        EventBridge.cameraYawOverride = 90f
        EventBridge.cameraPitchOverride = 45f

        EventBridge.reset()

        assertFalse(EventBridge.suppressHurtCam)
        assertFalse(EventBridge.keepMovementInGui)
        assertFalse(EventBridge.guiMovementKeepSprint)
        assertFalse(EventBridge.guiMovementOnlyInventory)
        assertNull(EventBridge.cameraYawOverride)
        assertNull(EventBridge.cameraPitchOverride)
    }
}
