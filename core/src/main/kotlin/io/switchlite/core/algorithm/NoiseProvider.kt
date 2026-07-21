package io.switchlite.core.algorithm

/**
 * Interface for noise generation in strategies.
 * All implementations must be pure and have no Minecraft dependencies.
 */
interface NoiseProvider {
    /**
     * Apply noise to a value.
     * @param value The original value
     * @return The value with noise applied
     */
    fun apply(value: Float): Float
    
    /**
     * Apply noise to a double value.
     * @param value The original value
     * @return The value with noise applied
     */
    fun apply(value: Double): Double
}
