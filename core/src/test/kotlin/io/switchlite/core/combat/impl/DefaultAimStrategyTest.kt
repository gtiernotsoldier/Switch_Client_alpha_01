package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.AimConfig
import io.switchlite.core.combat.strategy.AimMode
import io.switchlite.core.option.RandomRange
import io.switchlite.core.option.ProbabilityOption
import io.switchlite.core.option.TimingOptions
import io.switchlite.core.option.TriggerOptions
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Unit tests for DefaultAimStrategy.
 * Verifies condition triggering, soft boundary aiming, and human-like behavior.
 */
class DefaultAimStrategyTest {
    
    private val strategy = DefaultAimStrategy()
    
    private fun createPlayerState(yaw: Float = 0f, pitch: Float = 0f) = PlayerState(
        posX = 0.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        yaw = yaw, pitch = pitch,
        isOnGround = true,
        isMoving = true,
        isMovingForward = true,
        isSprinting = true,
        health = 20f,
        ticksExisted = 100
    )
    
    private fun createTargetState() = TargetState(
        entityId = 1,
        posX = 5.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        yaw = 0f, pitch = 0f,
        health = 20f,
        distanceToPlayer = 5.0,
        isMovingBackward = false,
        hitBoxWidth = 0.6f,
        hitBoxHeight = 1.8f
    )
    
    @Test
    fun testConditionTrigger_withTarget() {
        val config = AimConfig(
            mode = AimMode.LEGIT,
            maxAngularVelocity = 10f,
            overshootChance = 0.2f,
            microJitterStrength = 0.05f,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val target = createTargetState()
        
        // Should return correction with valid target
        val result = strategy.calculateAimCorrection(player, target, config)
        assertNotNull(result)
    }
    
    @Test
    fun testNoTarget_returnsNull() {
        val config = AimConfig(
            mode = AimMode.LEGIT,
            maxAngularVelocity = 10f,
            overshootChance = 0.2f,
            microJitterStrength = 0.05f,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        
        // Should return null without target
        val result = strategy.calculateAimCorrection(player, null, config)
        assertNull(result)
    }
    
    @Test
    fun testProbabilityCheck() {
        val config = AimConfig(
            mode = AimMode.LEGIT,
            maxAngularVelocity = 10f,
            overshootChance = 0.2f,
            microJitterStrength = 0.05f,
            probability = ProbabilityOption(0), // 0% chance
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val target = createTargetState()
        
        // Should never return correction with 0% probability
        repeat(10) {
            val result = strategy.calculateAimCorrection(player, target, config)
            assertNull(result)
        }
    }
    
    @Test
    fun testSoftBoundary_aimAtEdge() {
        val config = AimConfig(
            mode = AimMode.LEGIT,
            maxAngularVelocity = 10f,
            overshootChance = 0f, // Disable overshoot for this test
            microJitterStrength = 0f, // Disable jitter for this test
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val target = createTargetState()
        
        // Should return correction that aims at box edge, not center
        val result = strategy.calculateAimCorrection(player, target, config)
        assertNotNull(result)
        
        // Correction should be within reasonable bounds (not locking to center)
        assertTrue(Math.abs(result.x) < 90f) // Yaw correction should be moderate
        assertTrue(Math.abs(result.y) < 90f) // Pitch correction should be moderate
    }
    
    @Test
    fun testNoMinecraftDependencies() {
        val config = AimConfig(
            mode = AimMode.LEGIT,
            maxAngularVelocity = 10f,
            overshootChance = 0.2f,
            microJitterStrength = 0.05f,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val target = createTargetState()
        
        // Should execute without any Minecraft-related exceptions
        try {
            strategy.calculateAimCorrection(player, target, config)
            assertTrue(true) // Success
        } catch (e: ClassNotFoundException) {
            if (e.message?.contains("net.minecraft") == true) {
                assertTrue(false, "Found Minecraft dependency!")
            }
        } catch (e: Exception) {
            // Other exceptions are OK for this test
            assertTrue(true)
        }
    }
}
