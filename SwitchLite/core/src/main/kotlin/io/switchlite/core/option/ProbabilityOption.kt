package io.switchlite.core.option

/**
 * Probability option for shared options library.
 * Used by Velocity module and other modules.
 */
class ProbabilityOption(private val probability: Int = 100) {
    /** Current probability value (0-100). */
    val current: Int get() = probability

    fun test(): Boolean {
        return (Math.random() * 100) < probability
    }
}
