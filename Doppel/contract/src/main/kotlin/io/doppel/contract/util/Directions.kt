package io.doppel.contract.util

import io.doppel.contract.math.PortableMath

/**
 * Frame conversions between a rotation and a world-space direction.
 *
 * This lives in `contract`, not in `core`/`core-rs`, deliberately. It is not a
 * decision: it converts one already-decided number (yaw, in degrees) into the
 * vector form the game expects. Both the adapter (motion composition,
 * knockback-source tests) and the condition gate need it, and they need it on
 * hot paths — `StrafeFix` runs per render frame — where an IPC round trip would
 * cost more than the entire computation.
 *
 * Everything here delegates to [PortableMath], the fdlibm transcription. That is
 * the point of keeping it in one place: `RotationCalculator.yawToDirection`,
 * `ConditionChecker` and `geom.rs::yaw_to_direction` all describe the same
 * physical direction, so they must agree to the last bit rather than to within
 * a tolerance. A second copy using `kotlin.math` would differ by 1 ulp on a
 * fraction of inputs and reintroduce exactly the class of bug the fdlibm port
 * was written to remove.
 */
object Directions {

    /**
     * Horizontal unit vector for [yaw], Minecraft convention: 0° faces +Z,
     * growing clockwise, hence the negated sine on X.
     */
    fun fromYaw(yaw: Float): Vec3 {
        val rad = PortableMath.toRadians(yaw.toDouble())
        return Vec3(-PortableMath.sin(rad), 0.0, PortableMath.cos(rad))
    }
}
