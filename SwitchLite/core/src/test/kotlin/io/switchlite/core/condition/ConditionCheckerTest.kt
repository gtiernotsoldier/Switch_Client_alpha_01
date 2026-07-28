package io.switchlite.core.condition

import io.switchlite.core.model.*
import io.switchlite.core.option.TriggerOptions
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConditionCheckerTest {

    // ── Helpers ──

    private fun groundPlayer() = PlayerState(
        name = "TestPlayer", position = Vec3(0.0, 64.0, 0.0),
        rotation = Vec2(0f, 0f),
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        onGround = true, isMoving = false, isMovingForward = false,
        isSprinting = false, health = 20f, hurtTime = 0,
        maxHurtResistantTime = 10, isBlocking = false, isUsingItem = false,
        isLookingAtTarget = false, isMining = false, isSneaking = false,
        selectedSlot = 0, weaponType = WeaponType.OTHER,
        isAttackKeyDown = false, ticks = 100
    )

    private fun targetAt(distance: Float = 3f) = TargetState(
        entityId = 1, name = "Target", position = Vec3(0.0, 64.0, distance.toDouble()),
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = 20f, hurtTime = 0,
        isMovingBackward = false, isGoingBack = false,
        isMovingTowardsPlayer = true, distance = distance,
        hitbox = Hitbox(-0.3, 63.2, distance - 0.3, 0.3, 65.0, distance + 0.3),
        id = 1
    )

    // ── 1.1 Default ──

    @Test
    fun `default options - all conditions pass`() {
        assertTrue(ConditionChecker.check(TriggerOptions(), groundPlayer(), targetAt()))
    }

    // ── 1.2 onlyGround ──

    @Test
    fun `onlyGround - passes when on ground`() {
        assertTrue(ConditionChecker.check(TriggerOptions(onlyGround = true), groundPlayer(), null))
    }

    @Test
    fun `onlyGround - blocks when in air`() {
        val player = groundPlayer().copy(onGround = false)
        assertFalse(ConditionChecker.check(TriggerOptions(onlyGround = true), player, null))
    }

    // ── 1.3 onlyAir ──

    @Test
    fun `onlyAir - passes when in air`() {
        val player = groundPlayer().copy(onGround = false)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyAir = true), player, null))
    }

    @Test
    fun `onlyAir - blocks when on ground`() {
        assertFalse(ConditionChecker.check(TriggerOptions(onlyAir = true), groundPlayer(), null))
    }

    // ── 1.4 disabledInAir ──

    @Test
    fun `disabledInAir - passes on ground`() {
        assertTrue(ConditionChecker.check(TriggerOptions(disabledInAir = true), groundPlayer(), null))
    }

    @Test
    fun `disabledInAir - blocks in air`() {
        val player = groundPlayer().copy(onGround = false)
        assertFalse(ConditionChecker.check(TriggerOptions(disabledInAir = true), player, null))
    }

    // ── 1.5 onlyMove ──

    @Test
    fun `onlyMove - passes when moving`() {
        val player = groundPlayer().copy(isMoving = true, motionX = 0.1, motionZ = 0.1)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyMove = true), player, null))
    }

    @Test
    fun `onlyMove - blocks when stationary`() {
        assertFalse(ConditionChecker.check(TriggerOptions(onlyMove = true), groundPlayer(), null))
    }

    // ── 1.6 onlyMoveForward ──

    @Test
    fun `onlyMoveForward - passes when moving forward`() {
        val player = groundPlayer().copy(isMovingForward = true)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyMoveForward = true), player, null))
    }

    @Test
    fun `onlyMoveForward - blocks when not moving forward`() {
        assertFalse(ConditionChecker.check(TriggerOptions(onlyMoveForward = true), groundPlayer(), null))
    }

    // ── 1.7 onlyMoveBackward ──

    @Test
    fun `onlyMoveBackward - passes when moving backward`() {
        val player = groundPlayer().copy(rotation = Vec2(0f, 0f), motionZ = -0.2)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyMoveBackward = true), player, null))
    }

    @Test
    fun `onlyMoveBackward - blocks when moving forward`() {
        val player = groundPlayer().copy(rotation = Vec2(0f, 0f), motionZ = 0.2)
        assertFalse(ConditionChecker.check(TriggerOptions(onlyMoveBackward = true), player, null))
    }

    // ── 1.8 onlyStrafe ──

    @Test
    fun `onlyStrafe - passes when strafing`() {
        val player = groundPlayer().copy(rotation = Vec2(0f, 0f), motionX = 0.2)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyStrafe = true), player, null))
    }

    // ── 1.9 onlyWhenTargetGoesBack ──

    @Test
    fun `onlyWhenTargetGoesBack - passes when target goes back`() {
        val target = targetAt().copy(isMovingBackward = true)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyWhenTargetGoesBack = true), groundPlayer(), target))
    }

    @Test
    fun `onlyWhenTargetGoesBack - skipped when target is null`() {
        assertTrue(ConditionChecker.check(TriggerOptions(onlyWhenTargetGoesBack = true), groundPlayer(), null))
    }

    // ── 1.10 onlyWhenTargetApproaches ──

    @Test
    fun `onlyWhenTargetApproaches - passes when approaching`() {
        val target = targetAt().copy(isMovingTowardsPlayer = true)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyWhenTargetApproaches = true), groundPlayer(), target))
    }

    @Test
    fun `onlyWhenTargetApproaches - blocks when retreating`() {
        val target = targetAt().copy(isMovingTowardsPlayer = false)
        assertFalse(ConditionChecker.check(TriggerOptions(onlyWhenTargetApproaches = true), groundPlayer(), target))
    }

    // ── 1.11 Distance ──

    @Test
    fun `minDistance - blocks when too close`() {
        assertFalse(ConditionChecker.check(TriggerOptions(minDistance = 2f), groundPlayer(), targetAt(1.5f)))
    }

    @Test
    fun `maxDistance - blocks when too far`() {
        assertFalse(ConditionChecker.check(TriggerOptions(maxDistance = 5f), groundPlayer(), targetAt(6f)))
    }

    @Test
    fun `distance range - passes when in range`() {
        assertTrue(ConditionChecker.check(TriggerOptions(minDistance = 2f, maxDistance = 5f), groundPlayer(), targetAt(3f)))
    }

    // ── 1.12 onLook ──

    @Test
    fun `onLook - blocks when no target`() {
        assertFalse(ConditionChecker.check(TriggerOptions(onLook = true), groundPlayer(), null))
    }

    @Test
    fun `onLook - passes when looking at target`() {
        val player = groundPlayer().copy(position = Vec3(0.0, 64.0, 0.0), rotation = Vec2(0f, 0f))
        val target = targetAt(3f)
        assertTrue(ConditionChecker.check(TriggerOptions(onLook = true, lookAngleThreshold = 30f), player, target))
    }

    @Test
    fun `onLook - blocks when not looking at target`() {
        val player = groundPlayer().copy(rotation = Vec2(90f, 0f))
        assertFalse(ConditionChecker.check(TriggerOptions(onLook = true, lookAngleThreshold = 30f), player, targetAt(3f)))
    }

    // ── 1.13 onlyCurrentView ──

    @Test
    fun `onlyCurrentView - passes when no target`() {
        assertTrue(ConditionChecker.check(TriggerOptions(onlyCurrentView = true), groundPlayer(), null))
    }

    // ── 1.14 onlyOnClick ──

    @Test
    fun `onlyOnClick - passes when attack key held`() {
        val player = groundPlayer().copy(isAttackKeyDown = true)
        assertTrue(ConditionChecker.check(TriggerOptions(onlyOnClick = true), player, null))
    }

    @Test
    fun `onlyOnClick - blocks when attack key not held`() {
        assertFalse(ConditionChecker.check(TriggerOptions(onlyOnClick = true), groundPlayer(), null))
    }

    // ── 1.15 disableOnMine ──

    @Test
    fun `disableOnMine - blocks when mining`() {
        val player = groundPlayer().copy(isMining = true)
        assertFalse(ConditionChecker.check(TriggerOptions(disableOnMine = true), player, null))
    }

    // ── 1.16 chance ──

    @Test
    fun `chance 100 - always passes`() {
        assertTrue(ConditionChecker.check(TriggerOptions(chance = 100), groundPlayer(), null))
    }

    @Test
    fun `chance 0 - always blocks`() {
        assertFalse(ConditionChecker.check(TriggerOptions(chance = 0), groundPlayer(), null))
    }

    // ── 1.17 compile ──

    @Test
    fun `compile returns reusable function`() {
        val fn = ConditionChecker.compile(TriggerOptions(onlyGround = true))
        assertTrue(fn(groundPlayer(), null))
        assertFalse(fn(groundPlayer().copy(onGround = false), null))
    }

    // ── 1.18 Multi-condition ──

    @Test
    fun `multiple conditions - all must pass`() {
        val options = TriggerOptions(onlyGround = true, onlyMove = true, minDistance = 1f)
        val player = groundPlayer().copy(isMoving = true, motionX = 0.1, motionZ = 0.1)
        assertTrue(ConditionChecker.check(options, player, targetAt(3f)))
        assertFalse(ConditionChecker.check(options, player.copy(onGround = false), targetAt(3f)))
    }
}
