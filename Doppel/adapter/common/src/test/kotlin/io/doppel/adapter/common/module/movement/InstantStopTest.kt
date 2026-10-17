package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.core.model.PlayerState
import io.doppel.core.strategy.click.WeaponType
import io.doppel.core.util.Vec2
import io.doppel.core.util.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Instant Stop — the tap must start on the release, end on its own, and never outlive the reason.
 *
 * These run through the real claim layer, so "the brake is over" means the game's S key is actually
 * released again, which is the failure mode that matters.
 */
class InstantStopTest {

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        EventBridge.physicalForwardDown = false
        EventBridge.physicalBackDown = false
        EventBridge.isKeyForwardDown = false
        EventBridge.isKeyBackDown = false
    }

    @AfterEach
    fun tearDown() {
        InstantStop.disable()
        EventBridge.reset()
    }

    private fun player(motionZ: Double = 0.3, yaw: Float = 0f) = PlayerState(
        name = "me", position = Vec3(0.0, 64.0, 0.0), rotation = Vec2(yaw, 0f),
        motionX = 0.0, motionY = 0.0, motionZ = motionZ,
        onGround = true, isMoving = true, isMovingForward = true, isSprinting = true,
        health = 20f, hurtTime = 0, maxHurtResistantTime = 10,
        isBlocking = false, isUsingItem = false, isLookingAtTarget = false,
        isMining = false, isSneaking = false, selectedSlot = 0,
        weaponType = WeaponType.OTHER, isAttackKeyDown = false, ticks = 0
    )

    private fun tick(p: PlayerState = player()) = EventBridge.onTick(p, null)

    private fun holdForward(down: Boolean) {
        EventBridge.physicalForwardDown = down
        EventBridge.isKeyForwardDown = down
    }

    @Test
    fun `releasing W taps S`() {
        holdForward(true)
        InstantStop.enable()
        tick()

        holdForward(false)
        tick()

        assertTrue(EventBridge.isKeyBackActive)
    }

    @Test
    fun `the tap ends on its own`() {
        holdForward(true)
        InstantStop.enable()
        tick()
        holdForward(false)
        tick()
        assertTrue(EventBridge.isKeyBackActive)

        // MaxTicks defaults to 2, plus the tick the window closes on.
        repeat(3) { tick() }

        assertFalse(EventBridge.isKeyBackActive)
    }

    @Test
    fun `pressing W again cancels the brake`() {
        holdForward(true)
        InstantStop.enable()
        tick()
        holdForward(false)
        tick()
        assertTrue(EventBridge.isKeyBackActive)

        holdForward(true)
        tick()

        assertFalse(EventBridge.isKeyBackActive, "the player changed their mind")
    }

    @Test
    fun `releasing W while standing still presses nothing`() {
        holdForward(true)
        InstantStop.enable()
        tick(player(motionZ = 0.0))

        holdForward(false)
        tick(player(motionZ = 0.0))

        assertFalse(EventBridge.isKeyBackActive)
    }

    @Test
    fun `already moving backwards presses nothing`() {
        holdForward(true)
        InstantStop.enable()
        tick(player(motionZ = -0.3))

        holdForward(false)
        tick(player(motionZ = -0.3))

        assertFalse(EventBridge.isKeyBackActive, "there is no forward momentum to brake")
    }

    @Test
    fun `disabling mid-brake releases the key`() {
        holdForward(true)
        InstantStop.enable()
        tick()
        holdForward(false)
        tick()
        assertTrue(EventBridge.isKeyBackActive)

        InstantStop.disable()

        assertFalse(EventBridge.isKeyBackActive)
    }
}
