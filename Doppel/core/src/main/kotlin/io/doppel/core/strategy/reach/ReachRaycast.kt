package io.doppel.core.strategy.reach

import io.doppel.contract.util.Geometry
import io.doppel.contract.util.Vec3

/**
 * Ray/AABB test kept as the reference implementation for `ReachRaycast`'s parity fixtures.
 *
 * The algorithm itself lives in [Geometry.intersectBox] — one implementation, shared with the
 * platform adapters, so the JVM here and the Forge raycast cannot drift apart. This object exists
 * so `ModuleSessionParityTest` can hold the Rust core to the same numbers; it is not on any
 * production path.
 */
object ReachRaycast {

    /** Slab-test a segment of length [maxDist]; returns the entry distance or null. */
    fun intersectBox(
        origin: Vec3,
        dir: Vec3,
        min: Vec3,
        max: Vec3,
        maxDist: Double
    ): Double? = Geometry.intersectBox(origin, dir, min, max, maxDist)
}
