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
     * Check if rotation difference is within FOV limits (axis-aligned box, yaw/pitch space).
     * Retained for backward-compat / tests; prefer [isWithinFov3D] for spherical FOV.
     */
    fun isWithinFov(diff: Vec2, horizontalFov: Float, verticalFov: Float): Boolean {
        return kotlin.math.abs(diff.yaw) <= horizontalFov / 2f && 
               kotlin.math.abs(diff.pitch) <= verticalFov / 2f
    }

    /**
     * True 3D spherical FOV check: the target point is "in view" when the angle between the
     * current aim direction and the direction to the target point is within [fov]/2 degrees.
     *
     * This is a cone (sphere cross-section at the target's distance) whose physical radius
     * grows linearly with distance — the standard FOV geometry.
     *
     * @param origin eye position (world).
     * @param aim current aim rotation (yaw, pitch in degrees).
     * @param targetPoint the aim target point in world space.
     * @param fov total cone angle in degrees (radius = fov / 2).
     */
    fun isWithinFov3D(origin: Vec3, aim: Vec2, targetPoint: Vec3, fov: Float): Boolean {
        val toTarget = Vec3(
            targetPoint.x - origin.x,
            targetPoint.y - origin.y,
            targetPoint.z - origin.z
        )
        val dist = toTarget.length()
        if (dist < 1e-6) return true
        val dir = aimToDirection(aim)
        val cosAngle = (dir.dot(toTarget) / dist).coerceIn(-1.0, 1.0)
        val angle = Math.toDegrees(kotlin.math.acos(cosAngle)).toFloat()
        return angle <= fov / 2f
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
                bestAngularDist = angularDist.toDouble()
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

    /**
     * Compute the entry point where the aim ray (from [origin] along [aim]) first intersects
     * the [hitbox]. Returns null if the ray misses the box.
     *
     * Used by NORMAL mode to "lock onto" the exact point the crosshair touches, and by LEGIT
     * to detect whether the crosshair is currently inside the box.
     */
    fun rayHitPoint(origin: Vec3, aim: Vec2, hitbox: Hitbox): Vec3? {
        val dir = aimToDirection(aim)
        val hit = rayAabbEntry(origin, dir, hitbox) ?: return null
        return Vec3(
            origin.x + dir.x * hit,
            origin.y + dir.y * hit,
            origin.z + dir.z * hit
        )
    }

    /**
     * Get the world point + rotation toward the closest point on the box surface to the current
     * aim ray. Unlike the edge-only variant, this picks the nearest surface point so LEGIT mode
     * "stops at the box edge" rather than hard-locking a corner.
     *
     * @return a [BoxEdgeTarget] holding the world point and the rotation toward it, or null if
     *         the ray already intersects the box (i.e. the crosshair is inside).
     */
    fun getBoxEdgeTarget(origin: Vec3, aim: Vec2, hitbox: Hitbox): BoxEdgeTarget? {
        val dir = aimToDirection(aim)
        if (rayAabbEntry(origin, dir, hitbox) != null) return null // already inside
        val surfacePoint = closestSurfacePoint(origin, dir, hitbox)
        return BoxEdgeTarget(surfacePoint, calculateRotation(origin, surfacePoint))
    }

    /** Result bundle for box-edge aiming: the world surface point and the rotation toward it. */
    data class BoxEdgeTarget(val world: Vec3, val rotation: Vec2)

    /**
     * World-space center point of an AABB hitbox. Used by SELF_ADAPTIVE (and Nemui-style pull)
     * to aim at the entity's center rather than its box edge.
     */
    fun hitboxCenterWorld(box: Hitbox): Vec3 = Vec3(
        (box.minX + box.maxX) / 2.0,
        (box.minY + box.maxY) / 2.0,
        (box.minZ + box.maxZ) / 2.0
    )

    /**
     * Compute the closest world point on the AABB surface to a ray (origin + dir*t, t>=0).
     * For each of the 6 faces, clamp the ray's intersection with the face plane onto the face
     * rectangle, and keep the point with the smallest distance to the ray.
     */
    private fun closestSurfacePoint(origin: Vec3, dir: Vec3, box: Hitbox): Vec3 {
        val faces = listOf(
            0 to box.minX, 0 to box.maxX,
            1 to box.minY, 1 to box.maxY,
            2 to box.minZ, 2 to box.maxZ
        )
        var best: Vec3? = null
        var bestDist = Double.MAX_VALUE
        for ((axis, value) in faces) {
            val p = closestPointOnFaceToRay(origin, dir, box, axis, value) ?: continue
            val d = distancePointToRay(p, origin, dir)
            if (d < bestDist) {
                bestDist = d
                best = p
            }
        }
        return best ?: boxCenter(box)
    }

    /**
     * Closest point on a face rectangle to the ray. [axis] is the fixed axis (0=x,1=y,2=z) and
     * [value] is its constant coordinate. The other two axes are free and bounded by the box.
     */
    private fun closestPointOnFaceToRay(
        origin: Vec3, dir: Vec3, box: Hitbox, axis: Int, value: Double
    ): Vec3? {
        val d = when (axis) {
            0 -> dir.x
            1 -> dir.y
            2 -> dir.z
            else -> 0.0
        }
        if (kotlin.math.abs(d) < 1e-9) return null // ray parallel to the plane
        val t = (value - originCoordinate(origin, axis)) / d
        if (t < 0.0) return null // plane behind the ray
        val px = origin.x + dir.x * t
        val py = origin.y + dir.y * t
        val pz = origin.z + dir.z * t
        val x = if (axis == 0) value else px.coerceIn(box.minX, box.maxX)
        val y = if (axis == 1) value else py.coerceIn(box.minY, box.maxY)
        val z = if (axis == 2) value else pz.coerceIn(box.minZ, box.maxZ)
        return Vec3(x, y, z)
    }

    private fun originCoordinate(origin: Vec3, axis: Int): Double = when (axis) {
        0 -> origin.x
        1 -> origin.y
        2 -> origin.z
        else -> 0.0
    }

    /** Perpendicular distance from a point to a ray (origin + dir*t, t>=0), in world units. */
    private fun distancePointToRay(p: Vec3, origin: Vec3, dir: Vec3): Double {
        val toPoint = p - origin
        val t = toPoint.dot(dir)
        if (t < 0.0) return toPoint.length()
        val proj = origin + dir * t
        return p.distanceTo(proj)
    }

    private fun boxCenter(box: Hitbox): Vec3 = Vec3(
        (box.minX + box.maxX) / 2.0,
        (box.minY + box.maxY) / 2.0,
        (box.minZ + box.maxZ) / 2.0
    )

    /**
     * Entry parameter t (>= 0) where a ray (origin + dir*t) first enters the AABB, or null if
     * the ray misses the box. Reuses the slab method.
     */
    private fun rayAabbEntry(origin: Vec3, dir: Vec3, box: Hitbox): Double? {
        var tmin = Double.NEGATIVE_INFINITY
        var tmax = Double.POSITIVE_INFINITY
        val axes = listOf(
            Triple(origin.x, dir.x, Pair(box.minX, box.maxX)),
            Triple(origin.y, dir.y, Pair(box.minY, box.maxY)),
            Triple(origin.z, dir.z, Pair(box.minZ, box.maxZ))
        )
        for ((o, d, range) in axes) {
            if (kotlin.math.abs(d) < 1e-8) {
                if (o < range.first || o > range.second) return null
            } else {
                val invD = 1.0 / d
                var t1 = (range.first - o) * invD
                var t2 = (range.second - o) * invD
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return null
            }
        }
        return if (tmax < 0.0) null else if (tmin < 0.0) 0.0 else tmin
    }


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
        val yawRad = Math.toRadians(aim.yaw.toDouble())
        val pitchRad = Math.toRadians(aim.pitch.toDouble())
        val cosPitch = kotlin.math.cos(pitchRad)
        return Vec3(
            -kotlin.math.sin(yawRad) * cosPitch,
            -kotlin.math.sin(pitchRad),
            kotlin.math.cos(yawRad) * cosPitch
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

    /**
     * Convert yaw (degrees, MC convention) to a horizontal unit direction vector.
     * yaw=0 faces south (+Z), yaw=90 faces west (-X).
     *
     * Used by knockback angle checks (JumpReset) and movement direction computation
     * (ConditionChecker).
     */
    fun yawToDirection(yaw: Float): Vec3 {
        val yawRad = Math.toRadians(yaw.toDouble())
        return Vec3(
            -kotlin.math.sin(yawRad),
            0.0,
            kotlin.math.cos(yawRad)
        )
    }
}
