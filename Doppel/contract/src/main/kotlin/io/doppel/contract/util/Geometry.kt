package io.doppel.contract.util

import io.doppel.contract.math.PortableMath
import io.doppel.contract.model.Hitbox

/**
 * Pure geometry shared by every platform adapter.
 *
 * These five functions used to live in `io.doppel.core` (four of them inside RotationCalculator,
 * one inside ReachRaycast). They are here rather than behind an IPC op on purpose: the aim
 * interpolation in EventBridge and the reach raycast in the Forge bridge run **per frame, per
 * entity**, not per tick. A loopback round trip is tens of microseconds plus a serialisation, and
 * the smoothing they feed is already tuned to sub-degree tolerance — paying that per frame would
 * reintroduce exactly the jitter the smoothing exists to remove.
 *
 * Everything decision-shaped (whether to aim, whether to click, which key to damp) still goes to
 * the Rust core. What is here is arithmetic on numbers already in hand.
 *
 * ## Two slab conventions, deliberately not merged
 *
 * [rayAabbEntry] and [intersectBox] are both slab tests and both are exact; they differ in what
 * they consider a valid hit:
 *
 * - [rayAabbEntry] treats the ray as infinite and clamps a negative entry to 0, so "the origin is
 *   already inside the box" is a hit at t=0.
 * - [intersectBox] is a segment: it starts t at 0 and stops at `maxDist`, so a box behind the
 *   origin is a miss rather than a hit at 0.
 *
 * Folding one into the other changes the answer at the boundaries (parallel axes, boxes behind the
 * eye, exactly-`maxDist` hits), and both call sites depend on their own convention today. Merging
 * them is a real change with its own tests, not a refactor to slip in here.
 */
object Geometry {

    /** Aim rotation (degrees) to a unit direction. Minecraft convention: yaw 0 faces +Z (south). */
    fun aimToDirection(aim: Vec2): Vec3 {
        val yawRad = PortableMath.toRadians(aim.yaw.toDouble())
        val pitchRad = PortableMath.toRadians(aim.pitch.toDouble())
        val cosPitch = PortableMath.cos(pitchRad)
        return Vec3(
            -PortableMath.sin(yawRad) * cosPitch,
            -PortableMath.sin(pitchRad),
            PortableMath.cos(yawRad) * cosPitch
        )
    }

    /**
     * Entry parameter t (>= 0) where an infinite ray (origin + dir*t) first enters the AABB, or
     * null if the ray misses. A ray whose origin is already inside returns 0.
     *
     * Axes with |dir| < 1e-8 are handled explicitly: the division would produce ±inf or NaN and
     * the comparisons that follow would then disagree with each other.
     */
    fun rayAabbEntry(origin: Vec3, dir: Vec3, box: Hitbox): Double? {
        var tmin = Double.NEGATIVE_INFINITY
        var tmax = Double.POSITIVE_INFINITY

        if (kotlin.math.abs(dir.x) < 1e-8) {
            if (origin.x < box.minX || origin.x > box.maxX) return null
        } else {
            val invD = 1.0 / dir.x
            var t1 = (box.minX - origin.x) * invD
            var t2 = (box.maxX - origin.x) * invD
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return null
        }

        if (kotlin.math.abs(dir.y) < 1e-8) {
            if (origin.y < box.minY || origin.y > box.maxY) return null
        } else {
            val invD = 1.0 / dir.y
            var t1 = (box.minY - origin.y) * invD
            var t2 = (box.maxY - origin.y) * invD
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return null
        }

        if (kotlin.math.abs(dir.z) < 1e-8) {
            if (origin.z < box.minZ || origin.z > box.maxZ) return null
        } else {
            val invD = 1.0 / dir.z
            var t1 = (box.minZ - origin.z) * invD
            var t2 = (box.maxZ - origin.z) * invD
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return null
        }

        return if (tmax < 0.0) null else if (tmin < 0.0) 0.0 else tmin
    }

    /** World point where the aim ray first enters [hitbox], or null if it misses. */
    fun rayHitPoint(origin: Vec3, aim: Vec2, hitbox: Hitbox): Vec3? {
        val dir = aimToDirection(aim)
        val hit = rayAabbEntry(origin, dir, hitbox) ?: return null
        return Vec3(
            origin.x + dir.x * hit,
            origin.y + dir.y * hit,
            origin.z + dir.z * hit
        )
    }

    /** Yaw/pitch (degrees) pointing from [from] to [to]. */
    fun calculateRotation(from: Vec3, to: Vec3): Vec2 {
        val diffX = to.x - from.x
        val diffY = to.y - from.y
        val diffZ = to.z - from.z

        val distance = kotlin.math.sqrt(diffX * diffX + diffZ * diffZ)
        val yaw = PortableMath.atan2(diffZ, diffX).toFloat() * (180f / kotlin.math.PI.toFloat()) - 90f
        val pitch = -(PortableMath.atan2(diffY, distance).toFloat() * (180f / kotlin.math.PI.toFloat()))

        return Vec2(yaw, pitch)
    }

    /**
     * Slab-test a **segment** from [origin] along [dir] (unit) of length [maxDist] against the AABB
     * given by [min]/[max]. Returns the entry distance along the ray, or null if it does not hit
     * within [maxDist]. A box entirely behind the origin is a miss.
     */
    fun intersectBox(
        origin: Vec3,
        dir: Vec3,
        min: Vec3,
        max: Vec3,
        maxDist: Double
    ): Double? {
        var tmin = 0.0
        var tmax = maxDist

        var t1 = (min.x - origin.x) / dir.x
        var t2 = (max.x - origin.x) / dir.x
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        if (t1 > tmin) tmin = t1
        if (t2 < tmax) tmax = t2
        if (tmin > tmax) return null

        t1 = (min.y - origin.y) / dir.y
        t2 = (max.y - origin.y) / dir.y
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        if (t1 > tmin) tmin = t1
        if (t2 < tmax) tmax = t2
        if (tmin > tmax) return null

        t1 = (min.z - origin.z) / dir.z
        t2 = (max.z - origin.z) / dir.z
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        if (t1 > tmin) tmin = t1
        if (t2 < tmax) tmax = t2
        if (tmin > tmax) return null

        return if (tmin >= 0) tmin else 0.0
    }
}
