package io.switchlite.core.util

/**
 * 3D vector class for mathematical operations
 * Pure math, zero game logic
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    
    constructor(x: Float, y: Float, z: Float) : this(x.toDouble(), y.toDouble(), z.toDouble())
    
    /**
     * Vector addition
     */
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    
    /**
     * Vector subtraction
     */
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    
    /**
     * Scalar multiplication
     */
    operator fun times(scalar: Double): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)
    
    operator fun times(scalar: Float): Vec3 = times(scalar.toDouble())
    
    /**
     * Vector length (magnitude)
     */
    fun length(): Double = kotlin.math.sqrt(x * x + y * y + z * z)
    
    /**
     * Normalize vector to unit length
     */
    fun normalize(): Vec3 {
        val len = length()
        return if (len > 0) times(1.0 / len) else Vec3(0.0, 0.0, 0.0)
    }
    
    /**
     * Dot product
     */
    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
    
    /**
     * Cross product
     */
    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
    
    /**
     * Distance to another vector
     */
    fun distanceTo(other: Vec3): Double = minus(other).length()
    
    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}
