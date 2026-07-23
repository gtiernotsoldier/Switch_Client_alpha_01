package io.switchlite.core.algorithm

import io.switchlite.core.model.Hitbox
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
    
    // ========== Hitbox Utilities ==========

    private const val EYE_HEIGHT = 1.62

    /**
     * Check if the player's current aim ray intersects the target hitbox.
     * Casts a ray from player eye position in the direction of currentAim,
     * tests intersection with the AABB hitbox.
     */
    fun isInsideHitbox(playerPos: Vec3, currentAim: Vec2, hitbox: Hitbox): Boolean {
        val eyePos = Vec3(playerPos.x, playerPos.y + EYE_HEIGHT, playerPos.z)
        val dir = aimToDirection(currentAim)
        return rayIntersectsAABB(eyePos, dir, hitbox)
    }

    /**
     * Get the rotation toward the closest point on the hitbox to the current aim.
     * Projects all 8 hitbox corners to rotation space, picks the one closest to currentAim.
     */
    fun getClosestBoxEdge(playerPos: Vec3, currentAim: Vec2, hitbox: Hitbox): Vec2 {
        val eyePos = Vec3(playerPos.x, playerPos.y + EYE_HEIGHT, playerPos.z)
        val corners = hitboxCorners(hitbox)

        var bestRotation: Vec2? = null
        var bestDiff = Float.MAX_VALUE

        for (corner in corners) {
            val rot = calculateRotation(eyePos, corner)
            val diff = kotlin.math.abs(normalizeAngle(rot.yaw - currentAim.yaw)) +
                       kotlin.math.abs(rot.pitch - currentAim.pitch)
            if (diff < bestDiff) {
                bestDiff = diff
                bestRotation = rot
            }
        }

        return bestRotation ?: currentAim
    }

    /**
     * Calculate the rotation to aim at the hitbox.
     * If lockOnCrosshair, aim at the center of the hitbox.
     * Otherwise, randomly sample a point within the hitbox.
     */
    fun calculateTargetPoint(playerPos: Vec3, hitbox: Hitbox, lockOnCrosshair: Boolean): Vec2 {
        val eyePos = Vec3(playerPos.x, playerPos.y + EYE_HEIGHT, playerPos.z)
        val targetPoint = if (lockOnCrosshair) {
            hitboxCenter(hitbox)
        } else {
            Vec3(
                randomInRange(hitbox.minX, hitbox.maxX),
                randomInRange(hitbox.minY, hitbox.maxY),
                randomInRange(hitbox.minZ, hitbox.maxZ)
            )
        }
        return calculateRotation(eyePos, targetPoint)
    }

    // ========== Private Helpers ==========

    /**
     * Convert aim rotation (yaw, pitch in degrees) to a unit direction vector.
     * Minecraft convention: yaw=0 faces south (-Z), yaw=90 faces west (-X).
     */
    private fun aimToDirection(aim: Vec2): Vec3 {
        val yawRad = (aim.yaw + 90f) * (kotlin.math.PI.toFloat() / 180f)
        val pitchRad = aim.pitch * (kotlin.math.PI.toFloat() / 180f)
        val cosPitch = kotlin.math.cos(pitchRad.toDouble()).toFloat()
        return Vec3(
            -kotlin.math.cos(yawRad.toDouble()).toFloat() * cosPitch,
            -kotlin.math.sin(pitchRad.toDouble()).toFloat(),
            -kotlin.math.sin(yawRad.toDouble()).toFloat() * cosPitch
        )
    }

    /**
     * Ray-AABB intersection test using the slab method.
     */
    private fun rayIntersectsAABB(origin: Vec3, dir: Vec3, box: Hitbox): Boolean {
        var tmin = Double.NEGATIVE_INFINITY
        var tmax = Double.POSITIVE_INFINITY

        val axes = listOf(
            Triple(origin.x, dir.x, Pair(box.minX, box.maxX)),
            Triple(origin.y, dir.y, Pair(box.minY, box.maxY)),
            Triple(origin.z, dir.z, Pair(box.minZ, box.maxZ))
        )

        for ((o, d, range) in axes) {
            if (kotlin.math.abs(d) < 1e-8) {
                // Ray is parallel to slab
                if (o < range.first || o > range.second) return false
            } else {
                val invD = 1.0 / d
                var t1 = (range.first - o) * invD
                var t2 = (range.second - o) * invD
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return false
            }
        }

        return tmax >= 0 && tmin <= tmax
    }

    /**
     * Get the 8 corners of an AABB hitbox.
     */
    private fun hitboxCorners(box: Hitbox): List<Vec3> {
        return listOf(
            Vec3(box.minX, box.minY, box.minZ),
            Vec3(box.minX, box.minY, box.maxZ),
            Vec3(box.minX, box.maxY, box.minZ),
            Vec3(box.minX, box.maxY, box.maxZ),
            Vec3(box.maxX, box.minY, box.minZ),
            Vec3(box.maxX, box.minY, box.maxZ),
            Vec3(box.maxX, box.maxY, box.minZ),
            Vec3(box.maxX, box.maxY, box.maxZ)
        )
    }

    /**
     * Get the center point of a hitbox.
     */
    private fun hitboxCenter(box: Hitbox): Vec3 {
        return Vec3(
            (box.minX + box.maxX) / 2.0,
            (box.minY + box.maxY) / 2.0,
            (box.minZ + box.maxZ) / 2.0
        )
    }

    /**
     * Random double in range [min, max].
     */
    private fun randomInRange(min: Double, max: Double): Double {
        return min + kotlin.random.Random.nextDouble() * (max - min)
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
