package io.switchlite.core.util

import kotlin.random.Random

/**
 * Math utilities for random number generation and other math operations.
 * Pure math, zero game dependencies.
 */
object MathUtils {
    
    /**
     * Generate random float in range [min, max].
     */
    fun randomFloat(min: Float, max: Float): Float {
        return min + Random.nextFloat() * (max - min)
    }
    
    /**
     * Generate random int in range [min, max] (inclusive).
     */
    fun randomInt(min: Int, max: Int): Int {
        return Random.nextInt(min, max + 1)
    }
    
    /**
     * Clamp value to range [min, max].
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }
    
    /**
     * Clamp double value to range [min, max].
     */
    fun clamp(value: Double, min: Double, max: Double): Double {
        return value.coerceIn(min, max)
    }
    
    /**
     * Linear interpolation.
     */
    fun lerp(start: Float, end: Float, factor: Float): Float {
        return start + (end - start) * factor
    }
    
    /**
     * Linear interpolation for double.
     */
    fun lerp(start: Double, end: Double, factor: Double): Double {
        return start + (end - start) * factor
    }
}
