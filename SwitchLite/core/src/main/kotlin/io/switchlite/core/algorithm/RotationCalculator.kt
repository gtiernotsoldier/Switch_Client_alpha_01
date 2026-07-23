package io.switchlite.core.algorithm

import io.switchlite.core.model.Hitbox
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import kotlin.random.Random

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
     * Interpolate between current and target rotation with a single smoothness factor.
     */
    fun interpolate(current: Vec2, target: Vec2, factor: Float): Vec2 {
        val clampedFactor = factor.coerceIn(0f, 1f)
        return Vec2(
            current.yaw + (target.yaw - current.yaw) * clampedFactor,
            current.pitch + (target.pitch - current.pitch) * clampedFactor
        )
    }

    /**
     * Interpolate with separate yaw and pitch factors.
     * Pitch typically moves slower than yaw for natural feel.
     */
    fun interpolate(current: Vec2, target: Vec2, yawFactor: Float, pitchFactor: Float): Vec2 {
        val yf = yawFactor.coerceIn(0f, 1f)
        val pf = pitchFactor.coerceIn(0f, 1f)
        return Vec2(
            current.yaw + (target.yaw - current.yaw) * yf,
            current.pitch + (target.pitch - current.pitch) * pf
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
     * Get the rotation toward the closest edge point on faces that face the player.
     * 
     * Step 1: Determine which faces of the hitbox are visible to the player.
     *   For each axis, if the player is outside the box range, the nearer face is visible.
     *   If inside the range, both faces on that axis are visible.
     * Step 2: Among visible faces, find the edge point closest to the current aim direction.
     */
    fun getClosestBoxEdge(playerPos: Vec3, currentAim: Vec2, hitbox: Hitbox): Vec2 {
        val eyePos = Vec3(playerPos.x, playerPos.y + EYE_HEIGHT, playerPos.z)

        // Determine visible faces based on player position relative to box
        val visibleFaces = mutableListOf<Face>()

        // X-axis faces
        if (eyePos.x < hitbox.minX) {
            visibleFaces.add(Face.MIN_X)
        } else if (eyePos.x > hitbox.maxX) {
            visibleFaces.add(Face.MAX_X)
        } else {
            // Inside X range — both faces visible
            val distMin = kotlin.math.abs(eyePos.x - hitbox.minX)
            val distMax = kotlin.math.abs(eyePos.x - hitbox.maxX)
            if (distMin <= distMax) visibleFaces.add(Face.MIN_X)
            if (distMax <= distMin) visibleFaces.add(Face.MAX_X)
        }

        // Y-axis faces
        if (eyePos.y < hitbox.minY) {
            visibleFaces.add(Face.MIN_Y)
        } else if (eyePos.y > hitbox.maxY) {
            visibleFaces.add(Face.MAX_Y)
        } else {
            val distMin = kotlin.math.abs(eyePos.y - hitbox.minY)
            val distMax = kotlin.math.abs(eyePos.y - hitbox.maxY)
            if (distMin <= distMax) visibleFaces.add(Face.MIN_Y)
            if (distMax <= distMin) visibleFaces.add(Face.MAX_Y)
        }

        // Z-axis faces
        if (eyePos.z < hitbox.minZ) {
            visibleFaces.add(Face.MIN_Z)
        } else if (eyePos.z > hitbox.maxZ) {
            visibleFaces.add(Face.MAX_Z)
        } else {
            val distMin = kotlin.math.abs(eyePos.z - hitbox.minZ)
            val distMax = kotlin.math.abs(eyePos.z - hitbox.maxZ)
            if (distMin <= distMax) visibleFaces.add(Face.MIN_Z)
            if (distMax <= distMin) visibleFaces.add(Face.MAX_Z)
        }

        // Fallback: if no faces detected (shouldn't happen), use all
        if (visibleFaces.isEmpty()) {
            visibleFaces.addAll(Face.values().toList())
        }

        // Collect edges belonging to visible faces
        val visibleEdges = buildVisibleEdges(visibleFaces, hitboxCorners(hitbox))

        // Find closest edge point to aim ray
        val aimDir = aimToDirection(currentAim)
        var bestPoint: Vec3? = null
        var bestAngularDist = Double.MAX_VALUE

        for (edge in visibleEdges) {
            val closest = closestPointOnSegmentToRay(edge.a, edge.b, eyePos, aimDir)
            val rot = calculateRotation(eyePos, closest)
            val yawDiff = kotlin.math.abs(normalizeAngle(rot.yaw - currentAim.yaw))
            val pitchDiff = kotlin.math.abs(rot.pitch - currentAim.pitch)
            val angularDist = yawDiff + pitchDiff
            if (angularDist < bestAngularDist) {
                bestAngularDist = angularDist
                bestPoint = closest
            }
        }

        return if (bestPoint != null) {
            calculateRotation(eyePos, bestPoint)
        } else {
            currentAim
        }
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

    private enum class Face { MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_Z, MAX_Z }

    private data class Edge(val a: Vec3, val b: Vec3)

    /**
     * Face-to-edge topology. Each face maps to 4 edges defined by corner index pairs.
     * Corner index mapping:
     *   0: (minX, minY, minZ)  1: (minX, minY, maxZ)
     *   2: (minX, maxY, minZ)  3: (minX, maxY, maxZ)
     *   4: (maxX, minY, minZ)  5: (maxX, minY, maxZ)
     *   6: (maxX, maxY, minZ)  7: (maxX, maxY, maxZ)
     */
    private val FACE_EDGE_TOPOLOGY: Map<Face, List<IntArray>> = mapOf(
        Face.MIN_X to listOf(
            intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2, 3)
        ),
        Face.MAX_X to listOf(
            intArrayOf(4, 5), intArrayOf(4, 6), intArrayOf(5, 7), intArrayOf(6, 7)
        ),
        Face.MIN_Y to listOf(
            intArrayOf(0, 1), intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(4, 5)
        ),
        Face.MAX_Y to listOf(
            intArrayOf(2, 3), intArrayOf(2, 6), intArrayOf(3, 7), intArrayOf(6, 7)
        ),
        Face.MIN_Z to listOf(
            intArrayOf(0, 2), intArrayOf(0, 4), intArrayOf(2, 6), intArrayOf(4, 6)
        ),
        Face.MAX_Z to listOf(
            intArrayOf(1, 3), intArrayOf(1, 5), intArrayOf(3, 7), intArrayOf(5, 7)
        ),
    )

    /**
     * Build world-space Edge objects for the given visible faces and hitbox.
     */
    private fun buildVisibleEdges(visibleFaces: List<Face>, corners: List<Vec3>): List<Edge> {
        val edges = mutableListOf<Edge>()
        for (face in visibleFaces) {
            val topology = FACE_EDGE_TOPOLOGY[face] ?: continue
            for ((i, j) in topology) {
                edges.add(Edge(corners[i], corners[j]))
            }
        }
        return edges
    }

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
     * Get the 8 corners of an AABB hitbox as world-space Vec3.
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
     * Find the closest point on segment AB to a ray (origin + dir*t, t>=0).
     * Returns the point on the segment.
     */
    private fun closestPointOnSegmentToRay(a: Vec3, b: Vec3, rayOrigin: Vec3, rayDir: Vec3): Vec3 {
        val segDir = Vec3(b.x - a.x, b.y - a.y, b.z - a.z)
        val segLenSq = segDir.dot(segDir)
        if (segLenSq < 1e-12) return a

        // Closest points between two lines: segment AB and ray
        // Line 1: a + segDir * s,  s in [0,1]
        // Line 2: rayOrigin + rayDir * t, t >= 0
        val r = Vec3(rayOrigin.x - a.x, rayOrigin.y - a.y, rayOrigin.z - a.z)
        val a_dot_a = segLenSq
        val a_dot_b = segDir.dot(rayDir)
        val b_dot_b = rayDir.dot(rayDir)
        val a_dot_r = segDir.dot(r)
        val b_dot_r = rayDir.dot(r)

        val denom = a_dot_a * b_dot_b - a_dot_b * a_dot_b
        var s: Double
        var t: Double

        if (kotlin.math.abs(denom) < 1e-12) {
            // Lines parallel
            s = 0.0
            t = a_dot_r / a_dot_a
        } else {
            s = (a_dot_r * b_dot_b - b_dot_r * a_dot_b) / denom
            t = (a_dot_r * a_dot_b - b_dot_r * a_dot_a) / denom
        }

        s = s.coerceIn(0.0, 1.0)
        t = t.coerceAtLeast(0.0)

        // The closest point on the segment
        return Vec3(
            a.x + segDir.x * s,
            a.y + segDir.y * s,
            a.z + segDir.z * s
        )
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
