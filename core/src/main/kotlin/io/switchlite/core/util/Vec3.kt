package io.switchlite.core.util

/**
 * Simple 3D vector class for mathematical operations.
 * No Minecraft dependencies.
 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double
) {
    fun length(): Double = Math.sqrt(x * x + y * y + z * z)
    
    fun scale(factor: Double): Vec3 = Vec3(x * factor, y * factor, z * factor)
    
    fun add(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    
    fun subtract(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
}
