package io.doppel.core.algorithm

/**
 * Interface for noise generation strategies.
 * Implementations provide different noise distributions (Gaussian, uniform, etc.)
 */
interface NoiseGenerator {
    fun next(): Float
    fun next(mean: Float, stdDev: Float): Float
    fun reset()
}
