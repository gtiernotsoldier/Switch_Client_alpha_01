package io.switchlite.core.option

/**
 * Random range definition for configuration
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
    }
}
