package io.switchlite.core.algorithm

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Gaussian noise generator for human-like behavior
 * Uses Box-Muller transform for normal distribution
 */
class GaussianNoise(
    private val mean: Float = 0f,
    private val stdDev: Float = 1f,
    private val random: Random = Random
) : NoiseProvider {
    
    private var spare: Float? = null
    
    override fun next(): Float {
        return next(mean, stdDev)
    }
    
    override fun next(mean: Float, stdDev: Float): Float {
        // Use spare value from previous calculation if available
        spare?.let { value ->
            spare = null
            return value * stdDev + mean
        }
        
        // Box-Muller transform
        var u = 0f
        var v = 0f
        var s = 0f
        
        do {
            u = random.nextFloat() * 2 - 1
            v = random.nextFloat() * 2 - 1
            s = u * u + v * v
        } while (s >= 1 || s == 0f)
        
        val mul = sqrt(-2.0 * ln(s) / s)
        val z0 = u * mul
        val z1 = v * mul
        
        // Save one value for next call
        spare = z1.toFloat()
        
        return (z0.toFloat() * stdDev + mean)
    }
    
    override fun reset() {
        spare = null
    }
    
    companion object {
        /**
         * Create a Gaussian noise with physiological constraints
         * Suitable for human reaction simulation
         */
        fun createHumanLike(): GaussianNoise {
            // Mean reaction time ~200ms, std dev ~50ms
            return GaussianNoise(mean = 200f, stdDev = 50f)
        }
        
        /**
         * Create noise for aim jitter (small deviations)
         */
        fun createAimJitter(): GaussianNoise {
            return GaussianNoise(mean = 0f, stdDev = 0.5f)
        }
    }
}

private fun ln(value: Double): Double = kotlin.math.ln(value)
