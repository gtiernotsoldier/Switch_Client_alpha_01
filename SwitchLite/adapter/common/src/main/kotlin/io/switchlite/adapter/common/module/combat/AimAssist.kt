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

    // ========== Overshoot State Machine ==========
    private enum class OvershootState { IDLE, OVERSHOOT, CORRECT }

    private var overshootState = OvershootState.IDLE
    private var overshootTarget: Vec2? = null
    private var overshootTicksRemaining = 0

    // ========== Reaction Delay ==========
    private var lastTargetId = -1
    private var reactionDelayTicks = 0

    // ========== Tick Listener Reference ==========
    private var tickListener: ((PlayerState, TargetState?) -> Unit)? = null

    // ========== Event Handler (Platform Agnostic) ==========
    
    /**
     * Called by EventBridge on every client tick.
     * Receives pure data snapshots (PlayerState, TargetState).
     * NO Minecraft object access allowed here.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        // 1. Safety Check
        if (target == null) {
            resetOvershootState()
            lastTargetId = -1
            return
        }

        // 2. Range Check (Horizontal Distance Validation — X/Z only, ignores height)
        val dx = player.position.x - target.position.x
        val dz = player.position.z - target.position.z
        val horizontalDistance = kotlin.math.sqrt(dx * dx + dz * dz)
        if (horizontalDistance < rangeMin || horizontalDistance > rangeMax) {
            resetOvershootState()
            return
        }

        // 3. Condition Check (Unified Engine)
        if (!conditionChecker.check(triggerOptions, player, target)) return

        // 4. Reaction Delay
        if (target.entityId != lastTargetId) {
            lastTargetId = target.entityId
            reactionDelayTicks = sampleReactionDelay()
            resetOvershootState()
        }
        if (reactionDelayTicks > 0) {
            reactionDelayTicks--
            return
        }

        // 5. Target Point Calculation
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

        // 6. FOV Check
        val rotationDiff = rotationCalculator.calculateDifference(player.rotation, targetPoint)
        if (!rotationCalculator.isWithinFov(rotationDiff, horizontalFov, verticalFov)) {
            return // Target out of FOV
        }

        // 7. Smoothing & Interpolation
        val yawFactor = aimSpeed / 20.0f * smoothness
        val pitchFactor = aimSpeed / 20.0f * smoothness * 0.6f

        // 8. Overshoot State Machine
        var finalRotation = when (overshootState) {
            OvershootState.IDLE -> {
                // Normal tracking — check if we should overshoot
                val interpolated = rotationCalculator.interpolate(
                    current = player.rotation,
                    target = targetPoint,
                    yawFactor = yawFactor,
                    pitchFactor = pitchFactor
                )
                // 15-25% chance to overshoot on significant direction changes
                val angularSize = kotlin.math.abs(rotationDiff.yaw) + kotlin.math.abs(rotationDiff.pitch)
                if (angularSize > 5f && NoiseProvider.nextUniform(0f, 1f) < 0.20f) {
                    // Transition to OVERSHOOT
                    overshootTarget = computeOvershootTarget(player.rotation, targetPoint)
                    overshootTicksRemaining = if (NoiseProvider.nextUniform(0f, 1f) < 0.5f) 1 else 2
                    overshootState = OvershootState.OVERSHOOT
                    val osTarget = overshootTarget ?: run { resetOvershootState(); return }
                    rotationCalculator.interpolate(
                        current = player.rotation,
                        target = osTarget,
                        yawFactor = yawFactor,
                        pitchFactor = pitchFactor
                    )
                } else {
                    interpolated
                }
            }
            OvershootState.OVERSHOOT -> {
                // Move toward the overshoot point
                val osTarget = overshootTarget ?: run { resetOvershootState(); return }
                val result = rotationCalculator.interpolate(
                    current = player.rotation,
                    target = osTarget,
                    yawFactor = yawFactor,
                    pitchFactor = pitchFactor
                )
                overshootTicksRemaining--
                if (overshootTicksRemaining <= 0) {
                    overshootState = OvershootState.CORRECT
                }
                result
            }
            OvershootState.CORRECT -> {
                // Smoothly correct back to the real target
                val result = rotationCalculator.interpolate(
                    current = player.rotation,
                    target = targetPoint,
                    yawFactor = yawFactor * 1.2f,
                    pitchFactor = pitchFactor * 1.2f
                )
                overshootState = OvershootState.IDLE
                overshootTarget = null
                result
            }
        }

        // 9. Noise Injection (Humanization)
        finalRotation = noiseProvider.applyWalk(finalRotation, noiseIntensity)

        // 10. Apply Rotation (Via Bridge - Platform Specific Implementation handles the write-back)
        EventBridge.setPlayerRotation(finalRotation)
    }

    // ========== Helper Methods (Pure Logic) ==========

    /**
     * Compute an overshoot target by offsetting beyond the real target
     * by 5-15% of the rotation delta.
     */
    private fun computeOvershootTarget(currentRotation: Vec2, realTarget: Vec2): Vec2 {
        val delta = rotationCalculator.calculateDifference(currentRotation, realTarget)
        val overshootPercent = 0.05f + NoiseProvider.nextUniform(0f, 1f) * 0.10f  // 5-15%
        return Vec2(
            realTarget.yaw + delta.yaw * overshootPercent,
            realTarget.pitch + delta.pitch * overshootPercent
        )
    }

    /**
     * Sample a reaction delay in ticks using a log-normal distribution.
     * Models human reaction time: median ~3 ticks at 20 TPS.
     */
    private fun sampleReactionDelay(): Int {
        val z = NoiseProvider.next(0f, 1f).toDouble()
        val delayTicks = kotlin.math.exp(1.1 + 0.35 * z)
        return delayTicks.toInt().coerceIn(1, 6)
    }

    private fun resetOvershootState() {
        overshootState = OvershootState.IDLE
        overshootTarget = null
        overshootTicksRemaining = 0
    }

    // ========== Lifecycle ==========
    
    override fun onEnable() {
        // Save listener reference for proper unregistration
        tickListener = { player, target ->
            if (enabled) onClientTick(player, target)
        }
        EventBridge.registerTickListener(tickListener!!)
    }

    override fun onDisable() {
        // Unregister using the same reference that was registered
        tickListener?.let { EventBridge.unregisterTickListener(it) }
        tickListener = null
        resetOvershootState()
        lastTargetId = -1
        reactionDelayTicks = 0
    }
}
