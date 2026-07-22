package io.switchlite.core.option

/**
 * Probability option for shared options library.
 * Used by Velocity module and other modules.
 */
class ProbabilityOption(private val probability: Int = 100) {
    fun test(): Boolean {
        return (Math.random() * 100) < probability
    }
}
