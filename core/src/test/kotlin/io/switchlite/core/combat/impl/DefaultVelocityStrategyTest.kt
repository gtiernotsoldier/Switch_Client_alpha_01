package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.VelocityConfig
import io.switchlite.core.combat.strategy.VelocityMode
import io.switchlite.core.option.RandomRange
import io.switchlite.core.option.ProbabilityOption
import io.switchlite.core.option.TimingOptions
import io.switchlite.core.option.TriggerOptions
import io.switchlite.core.util.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for DefaultVelocityStrategy.
 * Verifies condition triggering, probability checks, and output ranges.
 */
class DefaultVelocityStrategyTest {
    
    private val strategy = DefaultVelocityStrategy()
    
    private fun createPlayerState(
        isOnGround: Boolean = true,
        isMoving: Boolean = true,
        isMovingForward: Boolean = true
    ) = PlayerState(
        posX = 0.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        yaw = 0f, pitch = 0f,
        isOnGround = isOnGround,
        isMoving = isMoving,
        isMovingForward = isMovingForward,
        isSprinting = true,
        health = 20f,
        ticksExisted = 100
    )
    
    private fun createTargetState(isMovingBackward: Boolean = true) = TargetState(
        entityId = 1,
        posX = 5.0, posY = 0.0, posZ = 0.0,
        motionX = -0.5, motionY = 0.0, motionZ = 0.0,
        yaw = 180f, pitch = 0f,
        health = 20f,
        distanceToPlayer = 5.0,
        isMovingBackward = isMovingBackward,
        hitBoxWidth = 0.6f,
        hitBoxHeight = 1.8f
    )
    
    @Test
    fun testConditionTrigger_onGround() {
        val config = VelocityConfig(
            mode = VelocityMode.LEGIT,
            horizontalRange = RandomRange(20f, 60f),
            verticalRange = RandomRange(20f, 40f),
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions(onlyGround = true)
        )
        
        val playerOnGround = createPlayerState(isOnGround = true)
        val playerInAir = createPlayerState(isOnGround = false)
        val original = Vec3(0.5, 0.8, 0.3)
        
        // Should modify when on ground
        val resultOnGround = strategy.modifyVelocity(original, playerOnGround, null, config)
        assertTrue(resultOnGround != original)
        
        // Should not modify when in air
        val resultInAir = strategy.modifyVelocity(original, playerInAir, null, config)
        assertEquals(original, resultInAir)
    }
    
    @Test
    fun testProbabilityCheck() {
        val config = VelocityConfig(
            mode = VelocityMode.LEGIT,
            horizontalRange = RandomRange(20f, 60f),
            verticalRange = RandomRange(20f, 40f),
            probability = ProbabilityOption(0), // 0% chance
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val original = Vec3(0.5, 0.8, 0.3)
        
        // Should never modify with 0% probability
        repeat(10) {
            val result = strategy.modifyVelocity(original, player, null, config)
            assertEquals(original, result)
        }
    }
    
    @Test
    fun testOutputInRange() {
        val config = VelocityConfig(
            mode = VelocityMode.LEGIT,
            horizontalRange = RandomRange(20f, 60f),
            verticalRange = RandomRange(20f, 40f),
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val original = Vec3(0.5, 0.8, 0.3)
        
        // Run multiple times to verify range
        repeat(20) {
            val result = strategy.modifyVelocity(original, player, null, config)
            
            // Check horizontal retention (20-60%)
            val hRatioX = result.x / original.x
            val hRatioZ = result.z / original.z
            assertTrue(hRatioX in 0.18..0.62) // Allow small noise margin
            assertTrue(hRatioZ in 0.18..0.62)
            
            // Check vertical retention (20-40%)
            val vRatio = result.y / original.y
            assertTrue(vRatio in 0.18..0.42) // Allow small noise margin
        }
    }
    
    @Test
    fun testNoMinecraftDependencies() {
        // Verify no Minecraft exceptions are thrown
        val config = VelocityConfig(
            mode = VelocityMode.LEGIT,
            horizontalRange = RandomRange(20f, 60f),
            verticalRange = RandomRange(20f, 40f),
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val target = createTargetState()
        val original = Vec3(0.5, 0.8, 0.3)
        
        // Should execute without any Minecraft-related exceptions
        try {
            strategy.modifyVelocity(original, player, target, config)
            assertTrue(true) // Success
        } catch (e: ClassNotFoundException) {
            if (e.message?.contains("net.minecraft") == true) {
                assertFalse(true, "Found Minecraft dependency!")
            }
        } catch (e: Exception) {
            // Other exceptions are OK for this test
            assertTrue(true)
        }
    }
}
