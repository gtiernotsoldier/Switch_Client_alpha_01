package io.switchlite.core.algorithm

import io.switchlite.core.util.Vec3

/**
 * Pure mathematical vector operations.
 * No Minecraft dependencies.
 */
object VectorOperations {
    
    /**
     * Scale a vector by a factor.
     */
    fun scale(vec: Vec3, factor: Double): Vec3 {
        return vec.scale(factor)
    }
    
    /**
     * Scale X and Z components by one factor, Y by another.
     */
    fun scaleHorizontalVertical(vec: Vec3, hFactor: Double, vFactor: Double): Vec3 {
        return Vec3(vec.x * hFactor, vec.y * vFactor, vec.z * hFactor)
    }
    
    /**
     * Add two vectors.
     */
    fun add(a: Vec3, b: Vec3): Vec3 {
        return a.add(b)
    }
    
    /**
     * Subtract two vectors.
     */
    fun subtract(a: Vec3, b: Vec3): Vec3 {
        return a.subtract(b)
    }
    
    /**
     * Calculate vector length.
     */
    fun length(vec: Vec3): Double {
        return vec.length()
    }
    
    /**
     * Normalize a vector (unit length).
     */
    fun normalize(vec: Vec3): Vec3 {
        val len = vec.length()
        return if (len > 0) {
            Vec3(vec.x / len, vec.y / len, vec.z / len)
        } else {
            vec
        }
    }
}
