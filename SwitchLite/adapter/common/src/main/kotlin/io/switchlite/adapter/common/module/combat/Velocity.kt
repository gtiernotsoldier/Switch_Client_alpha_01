package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.VectorOperations
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.RandomRange
import io.switchlite.core.option.ProbabilityOption
import io.switchlite.core.option.TriggerOptions
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category

/**
 * Velocity Module
 * 
 * Architecture Compliance:
 * 1. Pure Logic: No Minecraft/Forge/Fabric imports.
 * 2. Unified Config: Uses delegated properties for hot-reloading.
 * 3. Platform Agnostic: Receives state via parameters.
 * 4. Core Dependency: Only calls pure math algorithms (VectorOperations, ProbabilityOption).
 * 5. No Raw Random: All randomness handled by ProbabilityOption and RandomRange.
 */
object Velocity : Module("Velocity", Category.COMBAT) {

    // ========== Configuration (Delegated Properties) ==========
    
    // Mode: LEGIT (conditional), DELAY (packet delay), CLICK (auto-clicker style)
    private val mode by enum("Mode", VelocityMode.LEGIT)

    // Horizontal/Vertical Reduction Ranges (Using RandomRange for unified sampling)
    private val horizontalRange by range("Horizontal", 0.4f..0.6f, 0.0f..1.0f)
    private val verticalRange by range("Vertical", 0.0f..0.0f, 0.0f..1.0f)

    // Probability Check (Replaces Random.nextInt(100))
    private val probability by probability("Chance", 100, 0..100)

    // Trigger Conditions (Unified Engine)
    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = true
        onlyMoveForward = true
        onlyWhenTargetGoesBack = false
        chance = 100 // Redundant with probability, but kept for legacy config compatibility
    }

    // Noise Intensity for Humanization
    private val noiseIntensity by float("NoiseIntensity", 0.02f, 0.0f..0.1f)

    // ========== Runtime Dependencies ==========
    private val conditionChecker = ConditionChecker
    private val noiseProvider = NoiseProvider

    // ========== Event Handler (Platform Agnostic) ==========

    /**
     * Called by EventBridge when a velocity packet is received.
     * Receives original motion vector and current game state.
     * Returns modified motion vector or null to cancel.
     */
    fun onVelocityPacket(originalMotion: Vec3, player: PlayerState, target: TargetState?): Vec3? {
        if (mode != VelocityMode.LEGIT) return originalMotion

        // 1. Condition Check (Unified Engine)
        if (!conditionChecker.check(triggerOptions, player, target)) {
            return originalMotion
        }

        // 2. Probability Check (Replaces Random.nextInt(100))
        // If test() returns false, we skip modification (return original)
        if (!probability.test()) {
            return originalMotion
        }

        // 3. Sample Reduction Factors (Replaces Random.nextFloat)
        // RandomRange.sample() handles the distribution logic
        val hFactor = horizontalRange.sample()
        val vFactor = verticalRange.sample()

        // 4. Apply Reduction using Core Algorithm
        var modifiedMotion = VectorOperations.scale(originalMotion, hFactor, vFactor, hFactor)

        // 5. Apply Noise (Humanization)
        if (noiseIntensity > 0.0f) {
            modifiedMotion = noiseProvider.apply(modifiedMotion, noiseIntensity)
        }

        return modifiedMotion
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        // Register packet listener via Bridge
        EventBridge.registerVelocityListener { original, player, target ->
            if (enabled) onVelocityPacket(original, player, target) else original
        }
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener(this::onVelocityPacket)
    }
}

enum class VelocityMode { LEGIT, DELAY, CLICK }
