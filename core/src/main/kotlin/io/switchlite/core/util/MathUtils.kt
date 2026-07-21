package io.switchlite.core.util

import kotlin.math.*

/**
 * Mathematical utility functions for core calculations.
 * All functions are pure and have no Minecraft dependencies.
 */
object MathUtils {
    
    private val random = java.util.Random()
    
    /**
     * Generate a random float within a range [min, max].
     */
    fun nextFloat(min: Float, max: Float): Float {
        require(min <= max) { "min must be <= max" }
        return min + (random.nextFloat() * (max - min))
    }
    
    /**
     * Generate a random double within a range [min, max].
     */
    fun nextDouble(min: Double, max: Double): Double {
        require(min <= max) { "min must be <= max" }
        return min + (random.nextDouble() * (max - min))
    }
    
    /**
     * Generate a random integer within a range [min, max] (inclusive).
     */
    fun nextInt(min: Int, max: Int): Int {
        require(min <= max) { "min must be <= max" }
        return if (min == max) min else random.nextInt((max - min) + 1) + min
    }
    
    /**
     * Clamp a value between min and max.
     */
    fun <T : Comparable<T>> clamp(value: T, min: T, max: T): T {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }
    
    /**
     * Linear interpolation between two values.
     */
    fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
    
    /**
     * Normalize an angle to [0, 360) range.
     */
    fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }
    
    /**
     * Calculate the smallest angle difference between two angles.
     */
    fun angleDifference(a: Float, b: Float): Float {
        var diff = (b - a) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }
    
    /**
     * Test probability (returns true with given percentage chance).
     */
    fun testProbability(percentage: Int): Boolean {
        require(percentage in 0..100) { "Percentage must be between 0 and 100" }
        return random.nextInt(100) < percentage
    }
}
