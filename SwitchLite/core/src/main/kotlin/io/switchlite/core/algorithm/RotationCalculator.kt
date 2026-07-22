package io.switchlite.core.algorithm

import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3

/**
 * Rotation calculator for aim assist and target tracking.
 * Pure math, zero game dependencies.
 */
object RotationCalculator {
    
    /**
     * Calculate rotation difference between current and target rotation.
     */
    fun calculateDifference(current: Vec2, target: Vec2): Vec2 {
        val yawDiff = normalizeAngle(target.yaw - current.yaw)
        val pitchDiff = target.pitch - current.pitch
        return Vec2(yawDiff, pitchDiff)
    }
    
    /**
     * Normalize angle to -180..180 range.
     */
    fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
    
    /**
     * Check if rotation difference is within FOV limits.
     */
    fun isWithinFov(diff: Vec2, horizontalFov: Float, verticalFov: Float): Boolean {
        return kotlin.math.abs(diff.yaw) <= horizontalFov / 2f && 
               kotlin.math.abs(diff.pitch) <= verticalFov / 2f
    }
    
    /**
     * Interpolate between current and target rotation with smoothness factor.
     */
    fun interpolate(current: Vec2, target: Vec2, factor: Float): Vec2 {
        val clampedFactor = factor.coerceIn(0f, 1f)
        return Vec2(
            current.yaw + (target.yaw - current.yaw) * clampedFactor,
            current.pitch + (target.pitch - current.pitch) * clampedFactor
        )
    }
    
    /**
     * Check if current rotation is inside target hitbox.
     */
    fun isInsideHitbox(current: Vec2, hitbox: ClosedFloatingPointRange<Float>): Boolean {
        return hitbox.contains(current.yaw)
    }
    
    /**
     * Get closest edge of hitbox for Legit mode.
     */
    fun getClosestBoxEdge(current: Vec2, hitbox: ClosedFloatingPointRange<Float>): Vec2 {
        val minY = hitbox.start
        val maxY = hitbox.endInclusive
        return when {
            current.yaw < minY -> Vec2(minY, current.pitch)
            current.yaw > maxY -> Vec2(maxY, current.pitch)
            else -> current
        }
    }
    
    /**
     * Calculate target point based on selection type.
     */
    fun calculateTargetPoint(hitbox: ClosedFloatingPointRange<Float>, lockOnCrosshair: Boolean): Vec2 {
        return if (lockOnCrosshair) {
            Vec2((hitbox.start + hitbox.endInclusive) / 2f, 0f)
        } else {
            Vec2(hitbox.start + kotlin.random.Random.nextFloat() * (hitbox.endInclusive - hitbox.start), 0f)
        }
    }
    
    /**
     * Calculate rotation from player position to target position.
     */
    fun calculateRotation(from: Vec3, to: Vec3): Vec2 {
        val diffX = to.x - from.x
        val diffY = to.y - from.y
        val diffZ = to.z - from.z
        
        val distance = kotlin.math.sqrt(diffX * diffX + diffZ * diffZ)
        val yaw = kotlin.math.atan2(diffZ, diffX).toFloat() * (180f / kotlin.math.PI.toFloat()) - 90f
        val pitch = -(kotlin.math.atan2(diffY, distance).toFloat() * (180f / kotlin.math.PI.toFloat()))
        
        return Vec2(yaw, pitch)
    }
}
