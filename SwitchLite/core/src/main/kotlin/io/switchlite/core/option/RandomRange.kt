package io.switchlite.core.option

import io.switchlite.core.util.MathUtils

/**
 * Random range definition for configuration.
 * Provides unified sampling methods for both Float and Int ranges.
 */
data class RandomRange(
    val min: Float,
    val max: Float
) {
    val closedRange: ClosedFloatingPointRange<Float> get() = min..max
    
    companion object {
        fun fromList(list: List<Float>): RandomRange {
            require(list.size == 2) { "Range must have exactly 2 values" }
            return RandomRange(list[0], list[1])
        }
        
        /**
         * Sample a random float value within [min, max] using Core's MathUtils.
         */
        fun sample(min: Float, max: Float): Float {
            return MathUtils.randomFloat(min, max)
        }
        
        /**
         * Sample a random integer value within [min, max] (inclusive).
         * Used for Click mode burst counts and tick delays.
         */
        fun sampleInt(min: Int, max: Int): Int {
            return MathUtils.randomInt(min, max)
        }
    }
}
