package io.switchlite.core.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mathematical utility functions for rotation and vector operations
 */
object MathUtils {
    
    const val DEG_TO_RAD = PI / 180.0
    const val RAD_TO_DEG = 180.0 / PI
    
    /**
     * Convert degrees to radians
     */
    fun toRadians(degrees: Float): Float = (degrees * DEG_TO_RAD).toFloat()
    
    /**
     * Convert radians to degrees
     */
    fun toDegrees(radians: Float): Float = (radians * RAD_TO_DEG).toFloat()
    
    /**
     * Normalize angle to [-180, 180] range
     */
    fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
    
    /**
     * Calculate shortest angle difference between two angles
     */
    fun angleDifference(a: Float, b: Float): Float {
        return normalizeAngle(b - a)
    }
    
    /**
     * Smooth interpolation between two values
     */
    fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t.coerceIn(0f, 1f)
    }
    
    /**
     * Map value from one range to another
     */
    fun map(value: Float, fromLow: Float, fromHigh: Float, toLow: Float, toHigh: Float): Float {
        return toLow + (value - fromLow) * (toHigh - toLow) / (fromHigh - fromLow)
    }
    
    /**
     * Clamp value within a range
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }
    
    /**
     * Generate random float in range
     */
    fun randomInRange(range: ClosedFloatingPointRange<Float>): Float {
        return range.start + (range.endInclusive - range.start) * Math.random().toFloat()
    }
}
