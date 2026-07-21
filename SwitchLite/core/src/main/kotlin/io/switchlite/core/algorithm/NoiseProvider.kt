package io.switchlite.core.algorithm

/**
 * Noise provider interface for human-like randomization
 */
interface NoiseProvider {
    /**
     * Generate next noise value
     * @return Random noise value
     */
    fun next(): Float
    
    /**
     * Generate noise with specific mean and standard deviation
     */
    fun next(mean: Float, stdDev: Float): Float
    
    /**
     * Reset noise generator state
     */
    fun reset()
}
