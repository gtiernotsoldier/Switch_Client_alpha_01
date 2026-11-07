package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.contract.model.PlayerState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.mockk.every
import io.mockk.mockk
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
 *
 * The sliding test itself now lives in the core (it used to be `StopTap.stillSliding`), so what is
 * under test here is the module's key handling, not the geometry: given the core's verdict, does
 * the tap start, hold and release correctly? The projection itself is checked against the Rust
 * implementation in `MovementParityTest`, alongside every other movement decision.
 */
class InstantStopTest {

    private lateinit var core: CoreStub

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        EventBridge.physicalForwardDown = false
        EventBridge.physicalBackDown = false
        EventBridge.isKeyForwardDown = false
        EventBridge.isKeyBackDown = false

        // Stand-in for the core. For yaw 0 the projection onto the released direction is just the
        // sign of motionZ, which is all these scenarios ask about.
        core = mockk(relaxed = true)
        every { core.movementSliding(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val motionZ = arg<Double>(3)
            val releasedForward = arg<Boolean>(5)
            val minSpeed = arg<Double>(6)
            (if (releasedForward) motionZ else -motionZ) > minSpeed
        }
        CoreLink.stub = core
    }

    @AfterEach
    fun tearDown() {
        InstantStop.disable()
        CoreLink.stub = null
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
