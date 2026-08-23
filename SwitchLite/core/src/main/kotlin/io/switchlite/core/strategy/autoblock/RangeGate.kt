package io.switchlite.core.strategy.autoblock

/**
 * Core distance-gate algorithm for AutoBlock (and other range-gated modules).
 *
 * Decides whether a target is "in attack range" under the active [RangeMode]:
 *   - InRange: strictly inside [minDistance, maxDistance] on every tick.
 *   - OnEnter: engage once the target enters [minDistance, maxDistance], then HOLD until it
 *     leaves beyond maxDistance + hysteresis (no flicker at the boundary).
 *
 * Pure algorithm, zero platform dependencies. The module owns a [State] instance and passes the
 * per-tick distance + config here each tick; the module keeps the trigger/business logic.
 */
object RangeGate {

    enum class Mode { IN_RANGE, ON_ENTER }

    /** Default hysteresis margin (blocks) for the ON_ENTER mode. */
    const val DEFAULT_HYSTERESIS = 1.5f

    /** Mutable latch state for the ON_ENTER hysteresis. */
    class State {
        var engaged: Boolean = false
        fun reset() { engaged = false }
    }

    data class Config(
        val mode: Mode,
        val minDistance: Float,
        val maxDistance: Float,
        val hysteresis: Float = DEFAULT_HYSTERESIS
    )

    /**
     * Evaluate whether the given distance is "in range".
     *
     * @param state  per-module latch state (may be null when no hysteresis is needed).
     * @param distance target distance in blocks (null = no target → out of range + reset latch).
     */
    fun compute(state: State?, config: Config, distance: Float?): Boolean {
        if (distance == null) {
            state?.reset()
            return false
        }
        val within = distance >= config.minDistance && distance <= config.maxDistance
        return when (config.mode) {
            Mode.IN_RANGE -> within
            Mode.ON_ENTER -> {
                val s = state ?: return within
                if (within) s.engaged = true
                else if (distance > config.maxDistance + config.hysteresis) s.engaged = false
                s.engaged && distance >= config.minDistance
            }
        }
    }
}
