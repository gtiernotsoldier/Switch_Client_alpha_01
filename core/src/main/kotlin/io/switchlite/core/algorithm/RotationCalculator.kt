package io.switchlite.core.algorithm

import io.switchlite.core.util.Vec2
import io.switchlite.core.util.MathUtils

/**
 * Rotation calculations for aim assistance.
 * All functions are pure and have no Minecraft dependencies.
 */
object RotationCalculator {
    
    /**
     * Calculate the rotation needed to look at a target point.
     * @param playerPos Player position (x, y, z)
     * @param targetPos Target position (x, y, z)
     * @return Vec2 containing yaw and pitch changes needed
     */
    fun calculateRotation(playerPos: io.switchlite.core.util.Vec3, targetPos: io.switchlite.core.util.Vec3): Vec2 {
        val diffX = targetPos.x - playerPos.x
        val diffY = targetPos.y - playerPos.y
        val diffZ = targetPos.z - playerPos.z
        
        val yaw = Math.toDegrees(Math.atan2(diffX, diffZ)).toFloat()
        val pitch = Math.toDegrees(-Math.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ))).toFloat()
        
        return Vec2(yaw, pitch)
    }
    
    /**
     * Get the edge point of a hitbox for soft-boundary aiming.
     * @param playerPos Player position
     * @param targetPos Target center position
     * @param hitBoxWidth Target hitbox width
     * @param hitBoxHeight Target hitbox height
     * @return Point on the edge of the hitbox closest to player's crosshair
     */
    fun getBoxEdge(
        playerPos: io.switchlite.core.util.Vec3,
        targetPos: io.switchlite.core.util.Vec3,
        hitBoxWidth: Float,
        hitBoxHeight: Float
    ): io.switchlite.core.util.Vec3 {
        val direction = VectorOperations.normalize(
            io.switchlite.core.util.Vec3(
                targetPos.x - playerPos.x,
                targetPos.y - playerPos.y,
                targetPos.z - playerPos.z
            )
        )
        
        // Calculate edge point (offset from center by half hitbox size)
        val halfWidth = hitBoxWidth / 2f
        val edgeOffsetX = direction.x * halfWidth
        val edgeOffsetY = direction.y * (hitBoxHeight / 2f)
        val edgeOffsetZ = direction.z * halfWidth
        
        return io.switchlite.core.util.Vec3(
            targetPos.x + edgeOffsetX,
            targetPos.y + edgeOffsetY,
            targetPos.z + edgeOffsetZ
        )
    }
    
    /**
     * Smoothly interpolate between two angles with human-like acceleration.
     * @param current Current angle
     * @param target Target angle
     * @param maxSpeed Maximum angular velocity (degrees per tick)
     * @param overshoot Probability of slightly overshooting the target
     * @return Interpolated angle
     */
    fun smoothAngle(current: Float, target: Float, maxSpeed: Float, overshoot: Float = 0f): Float {
        var diff = MathUtils.angleDifference(current, target)
        
        // Apply overshoot if configured
        if (overshoot > 0f && MathUtils.testProbability((overshoot * 100).toInt())) {
            diff *= 1.1f // Slightly overshoot
        }
        
        // Clamp to max speed
        diff = MathUtils.clamp(diff, -maxSpeed, maxSpeed)
        
        return current + diff
    }
}
