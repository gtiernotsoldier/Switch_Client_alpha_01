package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.VectorOperations
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.triggerOptions

/**
 * Velocity Module (Knockback Modifier)
 * 
 * Architecture Compliance:
 * 1. Pure Logic: No Minecraft/Forge/Fabric imports.
 * 2. Unified Config: Uses delegated properties for hot-reloading.
 * 3. Platform Agnostic: Receives state via parameters, not direct game access.
 * 4. Core Dependency: Only calls pure math algorithms from core/algorithm.
 */
object Velocity : Module("Velocity", Category.COMBAT) {

    // ========== Configuration (Delegated Properties) ==========
    // Reduction factors
    private val horizontalMin by float("HorizontalMin", 0.4f, 0.0f..1.0f)
    private val horizontalMax by float("HorizontalMax", 0.6f, 0.0f..1.0f)
    private val verticalMin by float("VerticalMin", 0.4f, 0.0f..1.0f)
    private val verticalMax by float("VerticalMax", 0.6f, 0.0f..1.0f)

    // Behavior settings
    private val chance by int("Chance", 100, 0..100, "%")
    private val noiseIntensity by float("NoiseIntensity", 0.02f, 0.0f..0.5f)

    // Trigger conditions (Unified Engine)
    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = true
        onlyMoveForward = true
        onlyWhenTargetGoesBack = false
        disabledInAir = true
        chance = 100
    }

    // Mode selection
    private val legitMode by boolean("LegitMode", true)

    // ========== Runtime Dependencies ==========
    private val vectorOps = VectorOperations
    private val noiseProvider = NoiseProvider
    private val conditionChecker = ConditionChecker

    // ========== Event Handler (Platform Agnostic) ==========
    
    /**
     * Called by EventBridge when a velocity packet is received.
     * Receives original motion vector and current player state.
     * Returns modified motion vector or null to cancel modification.
     */
    fun onVelocityPacket(originalMotion: Vec3, player: PlayerState, target: TargetState?): Vec3? {
        // 1. Safety Check
        if (originalMotion == Vec3.ZERO) return null

        // 2. Condition Check (Unified Engine)
        if (!conditionChecker.check(triggerOptions, player, target)) {
            return null // Conditions not met, pass through
        }

        // 3. Probability Check
        if (chance < 100 && kotlin.random.Random.nextInt(100) >= chance) {
            return null // Chance failed, pass through
        }

        // 4. Calculate Reduction Factors (Randomized within range)
        val hFactor = kotlin.random.Random.nextFloat(horizontalMin, horizontalMax + 0.001f)
        val vFactor = kotlin.random.Random.nextFloat(verticalMin, verticalMax + 0.001f)

        // 5. Apply Scaling (Core Algorithm)
        var modifiedMotion = vectorOps.scale(originalMotion, hFactor, vFactor, hFactor)

        // 6. Noise Injection (Humanization - only in Legit mode)
        if (legitMode) {
            modifiedMotion = noiseProvider.apply(modifiedMotion, noiseIntensity)
        }

        // 7. Return modified vector (Bridge handles the actual application)
        return modifiedMotion
    }

    // ========== Helper Methods ==========
    
    override fun onEnable() {
        // Register packet listener via Bridge
        EventBridge.registerVelocityListener { original, player, target ->
            if (enabled) onVelocityPacket(original, player, target) else null
        }
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener(this::onVelocityPacket)
    }
}
