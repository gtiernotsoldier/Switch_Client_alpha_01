package io.switchlite.core.option

/**
 * Timing options for delayed actions
 */
data class TimingOptions(
    val delayTicks: Int = 0,
    val delayMs: Int = 0,
    val durationTicks: Int = 0,
    val intervalTicks: Int = 0,
    val randomizeDelay: Boolean = false,
    val delayVariationTicks: Int = 0
)
