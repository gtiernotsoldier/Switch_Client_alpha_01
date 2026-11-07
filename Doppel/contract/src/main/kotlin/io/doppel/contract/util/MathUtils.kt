package io.doppel.contract.util

import kotlin.random.Random

/**
 * Math utilities for random number generation and other math operations.
 * Pure math, zero game dependencies.
 */
object MathUtils {
    
    /**
     * Generate random float in range [min, max].
     *
     * Swapped bounds are ordered rather than trusted: a `Min` above its `Max` would otherwise return
     * values *below* the minimum. The option layer keeps ranges ordered (`ConfigManager`), so this
     * is the backstop for anything that reads a pair before that.
     */
    fun randomFloat(min: Float, max: Float): Float {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return lo + Random.nextFloat() * (hi - lo)
    }

    /**
     * Generate random int in range [min, max] (inclusive).
     *
     * `Random.nextInt` throws when `min > max`, which is a whole-tick crash rather than a bad value,
     * so the bounds are ordered here too.
     */
    fun randomInt(min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return Random.nextInt(lo, hi + 1)
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
