package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.Vec2
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.enum
import io.switchlite.adapter.common.option.triggerOptions

/**
 * AimAssist Module
 * 
 * Architecture Compliance:
 * 1. Pure Logic: No Minecraft/Forge/Fabric imports.
 * 2. Unified Config: Uses delegated properties for hot-reloading.
 * 3. Platform Agnostic: Receives state via parameters, not direct game access.
 * 4. Core Dependency: Only calls pure math algorithms from core/algorithm.
 */
object AimAssist : Module("AimAssist", Category.COMBAT) {

    // ========== Configuration (Delegated Properties) ==========
    // Range settings
    private val rangeMin by float("RangeMin", 3.0f, 0.0f..10.0f, "blocks")
    private val rangeMax by float("RangeMax", 6.0f, 0.0f..10.0f, "blocks")

    // FOV settings
    private val horizontalFov by float("HorizontalFOV", 90.0f, 0.0f..360.0f, "degrees")
    private val verticalFov by float("VerticalFOV", 60.0f, 0.0f..360.0f, "degrees")

    // Behavior settings
    private val aimSpeed by int("AimSpeed", 8, 1..20, "%")
    private val smoothness by float("Smoothness", 0.85f, 0.0f..1.0f)
    private val noiseIntensity by float("NoiseIntensity", 0.05f, 0.0f..0.5f)

    // Target selection
    private val prioritizeDistance by boolean("PrioritizeDistance", true)
    private val lockOnCrosshair by boolean("LockOnCrosshair", false)

    // Trigger conditions (Unified Engine)
    private val triggerOptions by triggerOptions("Trigger") {
        onlyCurrentView = true
        disableOnMine = true
        onlyOnClick = true
        chance = 100
    }

    // Mode: LEGIT (box edge) vs NORMAL (center/random)
    private val mode by enum("Mode", AimMode.LEGIT)

    // ========== Runtime Dependencies (Injected by Core) ==========
    private val rotationCalculator = RotationCalculator
    private val noiseProvider = NoiseProvider
    private val conditionChecker = ConditionChecker

    // ========== Event Handler (Platform Agnostic) ==========
    
    /**
     * Called by EventBridge on every client tick.
     * Receives pure data snapshots (PlayerState, TargetState).
     * NO Minecraft object access allowed here.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        // 1. Safety Check
        if (target == null) return
        
        // 2. Range Check (Horizontal Distance Validation — X/Z only, ignores height)
        val dx = player.position.x - target.position.x
        val dz = player.position.z - target.position.z
        val horizontalDistance = kotlin.math.sqrt(dx * dx + dz * dz)
        if (horizontalDistance < rangeMin || horizontalDistance > rangeMax) return

        // 3. Condition Check (Unified Engine)
        if (!conditionChecker.check(triggerOptions, player, target)) return

        // 4. Target Point Calculation
        val targetPoint = when (mode) {
            AimMode.LEGIT -> {
                // Legit Mode: Only correct if outside hitbox, pull to edge
                if (rotationCalculator.isInsideHitbox(player.position, player.rotation, target.hitbox)) {
                    return // Inside box, do nothing (Human-like hesitation)
                }
                rotationCalculator.getClosestBoxEdge(player.position, player.rotation, target.hitbox)
            }
            AimMode.NORMAL -> {
                // Normal Mode: Lock to center or random point within box
                rotationCalculator.calculateTargetPoint(player.position, target.hitbox, lockOnCrosshair)
            }
        }

        // 5. FOV Check
        val rotationDiff = rotationCalculator.calculateDifference(player.rotation, targetPoint)
        if (!rotationCalculator.isWithinFov(rotationDiff, horizontalFov, verticalFov)) {
            return // Target out of FOV
        }

        // 6. Smoothing & Interpolation
        var finalRotation = rotationCalculator.interpolate(
            current = player.rotation,
            target = targetPoint,
            factor = aimSpeed / 20.0f * smoothness
        )

        // 7. Noise Injection (Humanization)
        finalRotation = noiseProvider.apply(finalRotation, noiseIntensity)

        // 8. Apply Rotation (Via Bridge - Platform Specific Implementation handles the write-back)
        EventBridge.setPlayerRotation(finalRotation)
    }

    // ========== Helper Methods (Pure Logic) ==========
    
    override fun onEnable() {
        // Register listener via Bridge
        EventBridge.registerTickListener { player, target ->
            if (enabled) onClientTick(player, target)
        }
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(this::onClientTick)
    }
}
