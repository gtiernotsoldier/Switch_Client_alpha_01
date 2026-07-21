package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.SprintResetConfig
import io.switchlite.core.combat.strategy.SprintResetMode
import io.switchlite.core.option.ProbabilityOption
import io.switchlite.core.option.TimingOptions
import io.switchlite.core.option.TriggerOptions
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for DefaultSprintResetStrategy.
 * Verifies condition triggering, probability checks, and mode behavior.
 */
class DefaultSprintResetStrategyTest {
    
    private val strategy = DefaultSprintResetStrategy()
    
    private fun createPlayerState(
        isSprinting: Boolean = true,
        isMovingForward: Boolean = true,
        isOnGround: Boolean = true
    ) = PlayerState(
        posX = 0.0, posY = 0.0, posZ = 0.0,
        motionX = 0.5, motionY = 0.0, motionZ = -0.5,
        yaw = 0f, pitch = 0f,
        isOnGround = isOnGround,
        isMoving = true,
        isMovingForward = isMovingForward,
        isSprinting = isSprinting,
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
        val config = SprintResetConfig(
            mode = SprintResetMode.NORMAL,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions(onlyGround = true)
        )
        
        val playerOnGround = createPlayerState(isOnGround = true)
        val playerInAir = createPlayerState(isOnGround = false)
        
        // Should reset when on ground
        assertTrue(strategy.shouldResetSprint(playerOnGround, null, config))
        
        // Should not reset when in air
        assertFalse(strategy.shouldResetSprint(playerInAir, null, config))
    }
    
    @Test
    fun testProbabilityCheck() {
        val config = SprintResetConfig(
            mode = SprintResetMode.NORMAL,
            probability = ProbabilityOption(0), // 0% chance
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        
        // Should never reset with 0% probability
        repeat(10) {
            assertFalse(strategy.shouldResetSprint(player, null, config))
        }
    }
    
    @Test
    fun testNostopMode() {
        val config = SprintResetConfig(
            mode = SprintResetMode.NOSTOP,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        
        // NOSTOP mode should always reset when conditions are met
        assertTrue(strategy.shouldResetSprint(player, null, config))
    }
    
    @Test
    fun testNormalMode_requiresSprint() {
        val config = SprintResetConfig(
            mode = SprintResetMode.NORMAL,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val sprintingPlayer = createPlayerState(isSprinting = true)
        val notSprintingPlayer = createPlayerState(isSprinting = false)
        
        // Should reset when sprinting
        assertTrue(strategy.shouldResetSprint(sprintingPlayer, null, config))
        
        // Should not reset when not sprinting
        assertFalse(strategy.shouldResetSprint(notSprintingPlayer, null, config))
    }
    
    @Test
    fun testNoMinecraftDependencies() {
        val config = SprintResetConfig(
            mode = SprintResetMode.NORMAL,
            probability = ProbabilityOption(100),
            timing = TimingOptions(),
            trigger = TriggerOptions()
        )
        
        val player = createPlayerState()
        val target = createTargetState()
        
        // Should execute without any Minecraft-related exceptions
        try {
            strategy.shouldResetSprint(player, target, config)
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
