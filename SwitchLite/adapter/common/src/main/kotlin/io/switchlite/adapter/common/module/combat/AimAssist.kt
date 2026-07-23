package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.model.Hitbox
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
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
    private val inViewSpeed by float("InViewSpeed", 35.0f, 0.0f..100.0f, "deg/s")
    private val outViewSpeed by float("OutViewSpeed", 10.0f, 0.0f..100.0f, "deg/s")
    private val minRotDiff by float("MinRotDiff", 0.5f, 0.0f..5.0f, "degrees")
    private val predictTicks by int("PredictTicks", 2, 0..5, "ticks")

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

        // 2. Reaction Delay (new target detection)
        if (target.entityId != lastTargetId) {
            lastTargetId = target.entityId
            reactionDelayTicks = sampleReactionDelay()
            resetOvershootState()
        }
        if (reactionDelayTicks > 0) {
            reactionDelayTicks--
            return
        }

        // 3. Range Check (Horizontal Distance Validation — X/Z only, ignores height)
        val dx = player.position.x - target.position.x
        val dz = player.position.z - target.position.z
        val horizontalDistance = kotlin.math.sqrt(dx * dx + dz * dz)
        if (horizontalDistance < rangeMin || horizontalDistance > rangeMax) {
            resetOvershootState()
            return
        }

        // 4. Condition Check (Unified Engine)
        if (!conditionChecker.check(triggerOptions, player, target)) return

        // 5. Position Prediction (Bug 3: add velocity-based prediction)
        // TODO: 需要 Core 层 model 添加 prevPosition 以实现更精确的速度计算
        // Using motionX/motionY/motionZ as velocity proxy
        val predictFactor = predictTicks.toFloat()
        val predictedPlayerPos = Vec3(
            player.position.x + player.motionX * predictFactor,
            player.position.y + player.motionY * predictFactor,
            player.position.z + player.motionZ * predictFactor
        )
        val predictedTargetPos = Vec3(
            target.position.x + target.motionX * predictFactor,
            target.position.y + target.motionY * predictFactor,
            target.position.z + target.motionZ * predictFactor
        )
        val predictedHitbox = Hitbox(
            target.hitbox.minX + target.motionX * predictFactor,
            target.hitbox.minY + target.motionY * predictFactor,
            target.hitbox.minZ + target.motionZ * predictFactor,
            target.hitbox.maxX + target.motionX * predictFactor,
            target.hitbox.maxY + target.motionY * predictFactor,
            target.hitbox.maxZ + target.motionZ * predictFactor
        )

        // 6. Target Point Calculation
        val targetPoint = when (mode) {
            AimMode.LEGIT -> {
                // Legit Mode: Only correct if outside hitbox, pull to edge
                if (rotationCalculator.isInsideHitbox(predictedPlayerPos, player.rotation, predictedHitbox)) {
                    // Inside box — use weak factor to track center, then return
                    val centerPoint = rotationCalculator.calculateTargetPoint(predictedPlayerPos, predictedHitbox, lockOnCrosshair = true)
                    val weakRotation = rotationCalculator.interpolate(
                        current = player.rotation,
                        target = centerPoint,
                        yawFactor = 0.02f,
                        pitchFactor = 0.01f
                    )
                    EventBridge.setPlayerRotation(weakRotation)
                    return
                }
                rotationCalculator.getClosestBoxEdge(predictedPlayerPos, player.rotation, predictedHitbox)
            }
            AimMode.NORMAL -> {
                // Normal Mode: Lock to center or random point within box
                rotationCalculator.calculateTargetPoint(predictedPlayerPos, predictedHitbox, lockOnCrosshair)
            }
        }

        // 7. FOV Check
        val rotationDiff = rotationCalculator.calculateDifference(player.rotation, targetPoint)
        if (!rotationCalculator.isWithinFov(rotationDiff, horizontalFov, verticalFov)) {
            return // Target out of FOV
        }

        // 8. Dynamic Speed Calculation (Bug 4: in-view vs out-of-view speed)
        val angularSize = kotlin.math.abs(rotationDiff.yaw) + kotlin.math.abs(rotationDiff.pitch)
        val baseSpeed = if (angularSize < horizontalFov * 0.5f) inViewSpeed else outViewSpeed
        val gaussian = NoiseProvider.next(0f, 1f)
        val jitteredSpeed = baseSpeed + gaussian * 0.5f
        val effectiveFactor = (jitteredSpeed / 180f).coerceIn(0.01f, 1f)
        val yawFactor = effectiveFactor
        val pitchFactor = effectiveFactor * 0.6f

        // 9. Overshoot State Machine (Bug 5: safe unwrap + angle normalization)
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
                    yawFactor = yawFactor * 1.2f,  // Slightly faster correction
                    pitchFactor = pitchFactor * 1.2f
                )
                overshootState = OvershootState.IDLE
                overshootTarget = null
                result
            }
        }

        // 10. Noise Injection (Bug 4: independent pulse instead of accumulating walk)
        val jitterYaw = if (NoiseProvider.nextUniform(0f, 1f) < 0.5f)
            (NoiseProvider.next(0f, 1f) * noiseIntensity).toFloat() else 0f
        val jitterPitch = if (NoiseProvider.nextUniform(0f, 1f) < 0.5f)
            (NoiseProvider.next(0f, 1f) * noiseIntensity * 0.5f).toFloat() else 0f
        finalRotation = Vec2(finalRotation.yaw + jitterYaw, finalRotation.pitch + jitterPitch)

        // 11. Minimum Rotation Diff Threshold (Bug 4: skip tiny adjustments)
        val finalDiff = rotationCalculator.calculateDifference(player.rotation, finalRotation)
        val finalAngularSize = kotlin.math.abs(finalDiff.yaw) + kotlin.math.abs(finalDiff.pitch)
        if (finalAngularSize < minRotDiff) {
            return // Rotation diff too small, skip packet
        }

        // 12. Apply Rotation
        EventBridge.setPlayerRotation(finalRotation)
    }

    // ========== Helper Methods ==========

    /**
     * Compute an overshoot target by offsetting beyond the real target
     * by 5-15% of the rotation delta.
     */
    private fun computeOvershootTarget(currentRotation: Vec2, realTarget: Vec2): Vec2 {
        val delta = rotationCalculator.calculateDifference(currentRotation, realTarget)
        val overshootPercent = 0.05f + NoiseProvider.nextUniform(0f, 1f) * 0.10f  // 5-15%
        
        // Calculate raw overshoot rotation
        val rawYaw = realTarget.yaw + delta.yaw * overshootPercent
        val rawPitch = realTarget.pitch + delta.pitch * overshootPercent
        
        // Normalize yaw to [-180, 180] range
        var normalizedYaw = rawYaw
        while (normalizedYaw > 180f) normalizedYaw -= 360f
        while (normalizedYaw < -180f) normalizedYaw += 360f
        
        // Clamp pitch to [-90, 90] range
        val clampedPitch = rawPitch.coerceIn(-90f, 90f)
        
        return Vec2(normalizedYaw, clampedPitch)
    }

    /**
     * Sample a reaction delay in ticks using a log-normal distribution.
     * Models human reaction time: median ~150ms (3 ticks at 20 TPS),
     * with realistic variance.
     */
    private fun sampleReactionDelay(): Int {
        // Log-normal: exp(mu + sigma * Z), where Z is standard normal
        // mu=ln(3)≈1.1 for 3 tick median, sigma=0.35 for realistic spread
        // Direct tick calculation, no unit conversion needed
        val z = NoiseProvider.next(0f, 1f).toDouble()
        val delayTicks = kotlin.math.exp(1.1 + 0.35 * z)
        return delayTicks.toInt().coerceIn(1, 6)  // Clamp to 1-6 ticks
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
