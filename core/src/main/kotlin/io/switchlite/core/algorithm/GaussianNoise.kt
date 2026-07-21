package io.switchlite.core.algorithm

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Gaussian noise implementation for human-like behavior simulation.
 * Uses Box-Muller transform for normal distribution.
 */
class GaussianNoise(
    private val mean: Double = 0.0,
    private val stdDev: Double = 1.0
) : NoiseProvider {
    
    override fun apply(value: Float): Float {
        val noise = nextGaussian()
        return (value + noise).toFloat()
    }
    
    override fun apply(value: Double): Double {
        val noise = nextGaussian()
        return value + noise
    }
    
    /**
     * Generate a random number from standard normal distribution.
     */
    private fun nextGaussian(): Double {
        // Box-Muller transform
        val u1 = Random.nextDouble()
        val u2 = Random.nextDouble()
        
        val z0 = sqrt(-2.0 * Math.ln(u1)) * Math.cos(2.0 * Math.PI * u2)
        return z0 * stdDev + mean
    }
    
    /**
     * Generate Gaussian noise with custom parameters.
     */
    fun nextGaussian(customMean: Double = mean, customStdDev: Double = stdDev): Double {
        val u1 = Random.nextDouble()
        val u2 = Random.nextDouble()
        
        val z0 = sqrt(-2.0 * Math.ln(u1)) * Math.cos(2.0 * Math.PI * u2)
        return z0 * customStdDev + customMean
    }
}
