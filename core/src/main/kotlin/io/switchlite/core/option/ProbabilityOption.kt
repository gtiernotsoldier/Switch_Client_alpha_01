package io.switchlite.core.option

/**
 * Probability configuration for chance-based execution.
 */
data class ProbabilityOption(
    val chance: Int = 100
) {
    init {
        require(chance in 0..100) { "Chance must be between 0 and 100" }
    }
    
    fun test(): Boolean = kotlin.random.Random.nextInt(100) < chance
}
