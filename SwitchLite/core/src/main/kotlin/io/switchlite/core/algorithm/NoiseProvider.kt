package io.switchlite.core.algorithm

import io.switchlite.core.util.Vec2
import kotlin.random.Random

/**
 * Noise provider for human-like randomization.
 * Provides both raw noise generation and rotation perturbation.
 */
object NoiseProvider {
    
    private var spare: Float? = null
    
    /**
     * Generate next noise value with default mean=0, stdDev=1.
     */
    fun next(): Float = next(0f, 1f)
    
    /**
     * Generate noise with specific mean and standard deviation.
     * Uses Box-Muller transform for normal distribution.
     */
    fun next(mean: Float, stdDev: Float): Float {
        spare?.let { value ->
            spare = null
            return value * stdDev + mean
        }
        
        var u = 0f
        var v = 0f
        var s = 0f
        
        do {
            u = Random.nextFloat() * 2 - 1
            v = Random.nextFloat() * 2 - 1
            s = u * u + v * v
        } while (s >= 1 || s == 0f)
        
        val mul = kotlin.math.sqrt(-2.0 * kotlin.math.ln(s.toDouble()) / s)
        val z0 = (u * mul).toFloat()
        val z1 = (v * mul).toFloat()
        
        spare = z1
        return z0 * stdDev + mean
    }
    
    /**
     * Apply noise perturbation to a rotation vector.
     * @param rotation Current rotation
     * @param intensity Noise intensity (0.0 = no noise, 1.0 = full noise)
     * @return Perturbed rotation
     */
    fun apply(rotation: Vec2, intensity: Float): Vec2 {
        if (intensity <= 0f) return rotation
        val yawNoise = next(0f, intensity)
        val pitchNoise = next(0f, intensity * 0.5f)
        return Vec2(rotation.yaw + yawNoise, rotation.pitch + pitchNoise)
    }
    
    /**
     * Reset noise generator state.
     */
    fun reset() {
        spare = null
    }
    
    /**
     * Create noise for aim jitter (small deviations).
     */
    fun createAimJitter(): NoiseProviderInstance {
        return NoiseProviderInstance(mean = 0f, stdDev = 0.5f)
    }
}

/**
 * Instance-based noise provider for cases where state isolation is needed.
 */
class NoiseProviderInstance(
    private val mean: Float = 0f,
    private val stdDev: Float = 1f
) {
    private var spare: Float? = null
    
    fun next(): Float {
        spare?.let { value ->
            spare = null
            return value * stdDev + mean
        }
        
        var u = 0f
        var v = 0f
        var s = 0f
        
        do {
            u = Random.nextFloat() * 2 - 1
            v = Random.nextFloat() * 2 - 1
            s = u * u + v * v
        } while (s >= 1 || s == 0f)
        
        val mul = kotlin.math.sqrt(-2.0 * kotlin.math.ln(s.toDouble()) / s)
        val z0 = (u * mul).toFloat()
        val z1 = (v * mul).toFloat()
        
        spare = z1
        return z0 * stdDev + mean
    }
    
    fun reset() {
        spare = null
    }
}
