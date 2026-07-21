package io.switchlite.core.util

/**
 * Simple 2D vector class for rotation calculations.
 * No Minecraft dependencies.
 */
data class Vec2(
    val x: Float,
    val y: Float
) {
    fun length(): Float = Math.sqrt(x * x + y * y).toFloat()
    
    fun scale(factor: Float): Vec2 = Vec2(x * factor, y * factor)
    
    fun add(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    
    fun subtract(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
}
