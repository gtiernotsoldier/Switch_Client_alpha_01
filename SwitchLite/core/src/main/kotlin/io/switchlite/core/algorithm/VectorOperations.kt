package io.switchlite.core.algorithm

import io.switchlite.core.util.Vec3

/**
 * Vector operations for velocity manipulation.
 * Pure math, zero game dependencies.
 */
object VectorOperations {
    
    /**
     * Scale vector with different factors for horizontal and vertical components.
     */
    fun scale(original: Vec3, horizontalFactor: Float, verticalFactor: Float, horizontalFactorZ: Float = horizontalFactor): Vec3 {
        return Vec3(
            original.x * horizontalFactor,
            original.y * verticalFactor,
            original.z * horizontalFactorZ
        )
    }
    
    /**
     * Add two vectors.
     */
    fun add(a: Vec3, b: Vec3): Vec3 {
        return Vec3(a.x + b.x, a.y + b.y, a.z + b.z)
    }
    
    /**
     * Subtract two vectors.
     */
    fun subtract(a: Vec3, b: Vec3): Vec3 {
        return Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
    }
    
    /**
     * Multiply vector by scalar.
     */
    fun multiply(vector: Vec3, scalar: Double): Vec3 {
        return Vec3(vector.x * scalar, vector.y * scalar, vector.z * scalar)
    }
    
    /**
     * Get vector length.
     */
    fun length(vector: Vec3): Double {
        return kotlin.math.sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z)
    }
    
    /**
     * Normalize vector.
     */
    fun normalize(vector: Vec3): Vec3 {
        val len = length(vector)
        return if (len > 0) multiply(vector, 1.0 / len) else Vec3.ZERO
    }
    
    /**
     * Dot product.
     */
    fun dot(a: Vec3, b: Vec3): Double {
        return a.x * b.x + a.y * b.y + a.z * b.z
    }
    
    /**
     * Cross product.
     */
    fun cross(a: Vec3, b: Vec3): Vec3 {
        return Vec3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        )
    }
    
    /**
     * Distance between two vectors.
     */
    fun distance(a: Vec3, b: Vec3): Double {
        return length(subtract(a, b))
    }
}
