package io.switchlite.core.option

/**
 * Random range configuration for floating-point values.
 */
data class RandomRange(
    val min: Float,
    val max: Float
) {
    init {
        require(min <= max) { "min must be <= max" }
    }
    
    fun sample(): Float = kotlin.random.Random.nextFloat() * (max - min) + min
}
