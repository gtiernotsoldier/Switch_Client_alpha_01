package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.contract.model.PlayerState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * NullMove through the real arbitration layer.
 *
 * The interesting assertions are on the *effective* key, not on the module's own claim: what
 * matters is that MC ends up seeing one key, and that the older key comes back untouched when the
 * module stops having an opinion.
 */
class NullMoveTest {

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        EventBridge.physicalForwardDown = false
        EventBridge.physicalBackDown = false
        EventBridge.physicalLeftDown = false
        EventBridge.physicalRightDown = false
        EventBridge.isKeyLeftDown = false
        EventBridge.isKeyRightDown = false
    }

    @AfterEach
    fun tearDown() {
        NullMove.disable()
        EventBridge.reset()
    }

    private fun tick() {
        EventBridge.onTick(player(), null)
    }

    private fun player() = PlayerState(
        name = "me", position = Vec3(0.0, 64.0, 0.0), rotation = Vec2(0f, 0f),
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        onGround = true, isMoving = false, isMovingForward = false, isSprinting = false,
        health = 20f, hurtTime = 0, maxHurtResistantTime = 10,
        isBlocking = false, isUsingItem = false, isLookingAtTarget = false,
        isMining = false, isSneaking = false, selectedSlot = 0,
        weaponType = WeaponType.OTHER, isAttackKeyDown = false, ticks = 0
    )

    @Test
    fun `holding A then adding D releases A`() {
        NullMove.enable()
        press(left = true, right = false)
        tick()
        assertTrue(EventBridge.isKeyLeftActive)

        press(left = true, right = true)
        tick()

        assertFalse(EventBridge.isKeyLeftActive, "the older key is the one that goes away")
        assertTrue(EventBridge.isKeyRightActive)
    }

    @Test
    fun `the older key comes back when the newer one is released`() {
        NullMove.enable()
        press(left = true, right = false)
        tick()
        press(left = true, right = true)
        tick()
        assertFalse(EventBridge.isKeyLeftActive)

        press(left = true, right = false)
        tick()

        assertTrue(EventBridge.isKeyLeftActive)
        assertFalse(EventBridge.isKeyRightActive)
    }

    @Test
    fun `both keys in one sample are left to vanilla`() {
        NullMove.enable()
        press(left = true, right = true)
        tick()

        assertTrue(EventBridge.isKeyLeftActive)
        assertTrue(EventBridge.isKeyRightActive)
    }

    @Test
    fun `the forward axis obeys the same rule`() {
        NullMove.enable()
        EventBridge.physicalForwardDown = true
        EventBridge.isKeyForwardDown = true
        tick()

        EventBridge.physicalBackDown = true
        EventBridge.isKeyBackDown = true
        tick()

        assertFalse(EventBridge.isKeyForwardActive)
        assertTrue(EventBridge.isKeyBackActive)
    }

    @Test
    fun `disabling gives every key back`() {
        NullMove.enable()
        press(left = true, right = false)
        tick()
        press(left = true, right = true)
        tick()
        assertFalse(EventBridge.isKeyLeftActive)

        NullMove.disable()

        assertTrue(EventBridge.isKeyLeftActive)
        assertTrue(EventBridge.isKeyRightActive)
    }

    /** Physical and logical state together — the module reads the former, the game the latter. */
    private fun press(left: Boolean, right: Boolean) {
        EventBridge.physicalLeftDown = left
        EventBridge.isKeyLeftDown = left
        EventBridge.physicalRightDown = right
        EventBridge.isKeyRightDown = right
    }
}
