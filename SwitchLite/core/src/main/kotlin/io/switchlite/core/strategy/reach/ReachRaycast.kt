package io.switchlite.core.strategy.reach

import io.switchlite.core.util.Vec3

/**
 * Core Reach algorithm — the ray/AABB intersection (slab test) used to find the nearest entity an
 * extended reach ray hits.
 *
 * Raven's Reach extends a ray from the player's eyes along the look vector and picks the nearest
 * entity whose (expanded) bounding box the segment intersects, then overwrites `objectMouseOver`.
 * The pure-math heart of that is the segment-vs-AABB slab test below; the platform adapter (Forge)
 * enumerates entities and calls [intersectBox] for each, keeping core 100% platform-free.
 *
 * Pure math — zero MC / platform dependencies.
 */
object ReachRaycast {

    /**
     * Slab-test a ray segment from [origin] along [dir] (unit, length [maxDist]) against an AABB
     * defined by [min]/[max] corners. Returns the entry distance along the ray, or null if no hit.
     * Classic Amanatides & Woo slab method.
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

        // X slab
        var t1 = (min.x - origin.x) / dir.x
        var t2 = (max.x - origin.x) / dir.x
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        if (t1 > tmin) tmin = t1
        if (t2 < tmax) tmax = t2
        if (tmin > tmax) return null

        // Y slab
        t1 = (min.y - origin.y) / dir.y
        t2 = (max.y - origin.y) / dir.y
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        if (t1 > tmin) tmin = t1
        if (t2 < tmax) tmax = t2
        if (tmin > tmax) return null

        // Z slab
        t1 = (min.z - origin.z) / dir.z
        t2 = (max.z - origin.z) / dir.z
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        if (t1 > tmin) tmin = t1
        if (t2 < tmax) tmax = t2
        if (tmin > tmax) return null

        return if (tmin >= 0) tmin else 0.0
    }
}
