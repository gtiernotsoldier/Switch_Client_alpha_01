package io.switchlite.core.util

import io.switchlite.core.combat.model.TargetState

object RotationMath {
    
    /**
     * Calculate rotation difference between two angles
     */
    fun calculateDifference(current: Vec2, target: Vec2): Vec2 {
        val yawDiff = normalizeAngle(target.yaw - current.yaw)
        val pitchDiff = target.pitch - current.pitch
        return Vec2(yawDiff, pitchDiff)
    }
    
    /**
     * Normalize angle to -180..180 range
     */
    fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
    
    /**
     * Check if rotation difference is within FOV limits
     */
    fun isWithinFov(diff: Vec2, horizontalFov: Float, verticalFov: Float): Boolean {
        return kotlin.math.abs(diff.yaw) <= horizontalFov / 2f && 
               kotlin.math.abs(diff.pitch) <= verticalFov / 2f
    }
    
    /**
     * Interpolate between current and target rotation with smoothness factor
     */
    fun interpolate(current: Vec2, diff: Vec2, smoothness: Float): Vec2 {
        val clampedSmoothness = smoothness.coerceIn(0f, 1f)
        return Vec2(
            current.yaw + diff.yaw * clampedSmoothness,
            current.pitch + diff.pitch * clampedSmoothness
        )
    }
    
    /**
     * Check if current rotation is inside target hitbox
     */
    fun isInsideHitbox(current: Vec2, hitbox: ClosedFloatingPointRange<Float>): Boolean {
        // Simplified check - in real implementation would use 3D bounding box
        return hitbox.contains(current.yaw)
    }
    
    /**
     * Get closest edge of hitbox for Legit mode
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
     * Calculate target point based on selection type
     */
    fun calculateTargetPoint(hitbox: ClosedFloatingPointRange<Float>, type: Any): Vec2 {
        // Simplified - would use TargetSelection enum in full implementation
        return Vec2((hitbox.start + hitbox.endInclusive) / 2f, 0f)
    }
}
