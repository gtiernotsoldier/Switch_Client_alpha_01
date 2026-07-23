package io.switchlite.core.algorithm

import io.switchlite.core.util.Vec2
import kotlin.random.Random

/**
 * Noise provider for human-like randomization.
 * Provides both raw noise generation and rotation perturbation.
 */
object NoiseProvider {
    
    private var spare: Float? = null
    
    // Time-dependent random walk state
    private var walkYawOffset: Float = 0f
    private var walkPitchOffset: Float = 0f
    
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
     * Apply independent per-frame noise (original behavior).
     */
    fun apply(rotation: Vec2, intensity: Float): Vec2 {
        if (intensity <= 0f) return rotation
        val yawNoise = next(0f, intensity)
        val pitchNoise = next(0f, intensity * 0.5f)
        return Vec2(rotation.yaw + yawNoise, rotation.pitch + pitchNoise)
    }

    /**
     * Apply time-dependent random walk noise.
     * Each tick adds a small Gaussian increment to the previous offset,
     * producing smooth, continuous drift rather than independent jitter.
     *
     * @param rotation Current rotation
     * @param intensity Max drift magnitude in degrees
     * @param decay How quickly the offset decays toward 0 each tick (0=none, 1=instant snap-back)
     */
    fun applyWalk(rotation: Vec2, intensity: Float, decay: Float = 0.1f): Vec2 {
        if (intensity <= 0f) return rotation

        // Random Gaussian increment scaled by intensity
        val yawIncrement = next(0f, intensity * 0.3f)
        val pitchIncrement = next(0f, intensity * 0.15f)

        // Decay toward zero (prevents unbounded drift)
        walkYawOffset = walkYawOffset * (1f - decay) + yawIncrement
        walkPitchOffset = walkPitchOffset * (1f - decay) + pitchIncrement

        // Clamp to max intensity
        walkYawOffset = walkYawOffset.coerceIn(-intensity, intensity)
        walkPitchOffset = walkPitchOffset.coerceIn(-intensity * 0.5f, intensity * 0.5f)

        return Vec2(rotation.yaw + walkYawOffset, rotation.pitch + walkPitchOffset)
    }
    
    /**
     * Reset noise generator state including random walk offsets.
     */
    fun reset() {
        spare = null
        walkYawOffset = 0f
        walkPitchOffset = 0f
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
